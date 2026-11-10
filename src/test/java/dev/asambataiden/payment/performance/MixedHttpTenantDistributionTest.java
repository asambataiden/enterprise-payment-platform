package dev.asambataiden.payment.performance;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Protects load-test correctness: a misleading workload can yield
 * reassuring performance numbers while starving three tenants of writes.
 */
class MixedHttpTenantDistributionTest {
    @Test
    void everyFiftyRequestsDistributesReadsAndWritesAcrossAllFourMerchants() {
        for (int block = 0; block < 4; block++) {
            int[][] count = new int[4][2]; // read, write
            for (int i = 1; i <= 50; i++) {
                int n = block * 50 + i;
                int merchant = MixedHttpTenantDistribution.merchantIndex(n);
                assertTrue(merchant >= 0 && merchant < 4);
                count[merchant][n % 5 == 0 ? 1 : 0]++;
            }
            assertArrayEquals(new int[] {28, 7}, count[0],
                    "Hot tenant must receive 70% of reads AND writes");
            for (int merchant = 1; merchant < 4; merchant++) {
                assertArrayEquals(new int[] {4, 1}, count[merchant],
                        "Cold tenant must receive 10% of each operation");
            }
        }
    }

    @Test
    void warmupSequenceMapsToAValidMerchantWithoutExternalState() {
        for (int n = -20; n < 0; n++) {
            int merchant = MixedHttpTenantDistribution.merchantIndex(n);
            assertTrue(merchant >= 0 && merchant < 4);
        }
        assertThrows(IllegalArgumentException.class,
                () -> MixedHttpTenantDistribution.merchantIndex(0));
        assertThrows(IllegalArgumentException.class,
                () -> MixedHttpTenantDistribution.merchantIndex(Integer.MIN_VALUE));
    }
}
