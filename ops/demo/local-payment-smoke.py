import base64
import json
import subprocess
import urllib.parse
import urllib.request
import uuid

KC = "enterprise-payment-platform-keycloak-1"
KCADM = "/opt/keycloak/bin/kcadm.sh"
CLIENT_ID = "payment-demo-cli"


def keycloak_admin_get(path, *args):
    result = subprocess.run(
        [
            "docker", "exec", KC, KCADM,
            "get", path, "-r", "payments", *args,
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    return json.loads(result.stdout)


# Resolve the generated UUID from the stable client ID.
clients = keycloak_admin_get(
    "clients",
    "-q", f"clientId={CLIENT_ID}",
)

if len(clients) != 1:
    raise RuntimeError(
        f"Expected one Keycloak client named {CLIENT_ID}; "
        f"found {len(clients)}"
    )

client_uuid = clients[0]["id"]

credential = keycloak_admin_get(
    f"clients/{client_uuid}/client-secret"
)

secret = credential.get("value")

if not secret:
    raise RuntimeError(
        "Keycloak returned no client secret. "
        "Verify that the imported confidential client "
        "has an active generated credential."
    )

print("PASS: Resolved Keycloak client dynamically")

token_url = (
    "http://localhost:8180/realms/payments"
    "/protocol/openid-connect/token"
)

body = urllib.parse.urlencode({
    "grant_type": "client_credentials",
    "client_id": "payment-demo-cli",
    "client_secret": secret,
}).encode()

request = urllib.request.Request(
    token_url,
    data=body,
    headers={"Content-Type": "application/x-www-form-urlencoded"},
)

with urllib.request.urlopen(request, timeout=15) as response:
    token = json.load(response)["access_token"]

# Inspect token claims locally.
# Spring Security will perform actual signature verification.
payload = token.split(".")[1]
payload += "=" * (-len(payload) % 4)
claims = json.loads(base64.urlsafe_b64decode(payload))

expected_scopes = {
    "payments:read",
    "payments:write",
    "payments:simulate",
}

assert claims["iss"] == "http://localhost:8180/realms/payments"
assert "enterprise-payment-service" in (
    [claims["aud"]] if isinstance(claims["aud"], str)
    else claims["aud"]
)
assert claims["merchant_id"] == "merchant-demo-001"
assert expected_scopes <= set(claims.get("scope", "").split())

print("PASS: JWT issuer")
print("PASS: JWT audience")
print("PASS: Merchant identity")
print("PASS: Payment scopes")

# Create a simulated payment.
payment_data = json.dumps({
    "amount": 22.50,
    "currency": "EUR",
    "merchantReference": "portfolio-demo-001",
}).encode()

payment_request = urllib.request.Request(
    "http://localhost:8080/api/v1/payments",
    data=payment_data,
    headers={
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "Idempotency-Key": str(uuid.uuid4()),
    },
    method="POST",
)

with urllib.request.urlopen(payment_request, timeout=15) as response:
    payment = json.load(response)
    assert response.status == 201
    print("PASS: Authenticated HTTP 201")
    print(json.dumps(payment, indent=2))

# Verify the authenticated payment lifecycle.
payment_id = payment["id"]
api_url = f"http://localhost:8080/api/v1/payments/{payment_id}"


def api_json(url, method="GET", data=None, extra_headers=None):
    headers = {"Authorization": f"Bearer {token}"}
    if extra_headers:
        headers.update(extra_headers)

    payload = None if data is None else json.dumps(data).encode()
    if payload is not None:
        headers["Content-Type"] = "application/json"

    request = urllib.request.Request(
        url, data=payload, headers=headers, method=method
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        return response.status, json.load(response)


for state in ("AUTHORIZED", "CAPTURED", "REFUNDED"):
    event_id = str(uuid.uuid4())
    status, updated = api_json(
        f"{api_url}/simulated-transitions",
        method="POST",
        data={"targetState": state},
        extra_headers={"Event-Id": event_id},
    )

    assert status == 200
    assert updated["id"] == payment_id
    assert updated["status"] == state
    print(f"PASS: Simulated transition to {state}")

status, current = api_json(api_url)
assert status == 200
assert current["status"] == "REFUNDED"
print("PASS: Final payment state")

status, history = api_json(f"{api_url}/history")
assert status == 200

states = [event["targetState"] for event in history["events"]]
assert len(states) == 3, states
assert set(states) == {"AUTHORIZED", "CAPTURED", "REFUNDED"}
assert history["hasMore"] is False

print("PASS: Three committed lifecycle events")
print("PASS: Payment history retrieval")
print("PASS: No additional historical events")
print("PASS: Authenticated payment lifecycle completed")
