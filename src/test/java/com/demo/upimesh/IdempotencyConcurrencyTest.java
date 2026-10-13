package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.service.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The killer test: simulates the "three bridges deliver at the same instant"
 * scenario the user explicitly cared about.
 */
@SpringBootTest
class IdempotencyConcurrencyTest {

    @Autowired private DemoService demoService;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private IdempotencyService idempotency;
    @Autowired private AccountRepository accounts;
    @Autowired private HybridCryptoService crypto;
    @Autowired private ServerKeyHolder serverKey;

    @BeforeEach
    void clear() {
        idempotency.clear();
    }

    @Test
    void singlePacketDeliveredByThreeBridgesSettlesExactlyOnce() throws Exception {
        // Capture starting balances
        BigDecimal aliceBefore = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobBefore = accounts.findById("bob@demo").orElseThrow().getBalance();

        // One packet, but we'll deliver it from 3 "bridges" simultaneously
        MeshPacket packet = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("100.00"), "1234", 5);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger settled = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();

        Future<?>[] futures = new Future[3];
        for (int i = 0; i < 3; i++) {
            final String node = "bridge-" + i;
            futures[i] = pool.submit(() -> {
                try {
                    start.await();
                    BridgeIngestionService.IngestResult r = bridge.ingest(packet, node, 3);
                    if ("SETTLED".equals(r.outcome())) settled.incrementAndGet();
                    else if ("DUPLICATE_DROPPED".equals(r.outcome())) duplicates.incrementAndGet();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        }

        start.countDown(); // release all 3 threads at once
        for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, settled.get(), "exactly one bridge should settle");
        assertEquals(2, duplicates.get(), "the other two should be duplicates");

        // Balance moved exactly once
        BigDecimal aliceAfter = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobAfter = accounts.findById("bob@demo").orElseThrow().getBalance();
        assertEquals(aliceBefore.subtract(new BigDecimal("100.00")), aliceAfter);
        assertEquals(bobBefore.add(new BigDecimal("100.00")), bobAfter);
    }

    @Test
    void tamperedCiphertextIsRejected() throws Exception {
        MeshPacket packet = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("50.00"), "1234", 5);

        // Flip a byte in the middle of the ciphertext
        char[] chars = packet.getCiphertext().toCharArray();
        chars[chars.length / 2] = chars[chars.length / 2] == 'A' ? 'B' : 'A';
        packet.setCiphertext(new String(chars));

        BridgeIngestionService.IngestResult r = bridge.ingest(packet, "bridge-x", 1);
        assertEquals("INVALID", r.outcome());
    }

    @Test
    void encryptDecryptRoundTrip() throws Exception {
        PaymentInstruction original = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("123.45"),
                "abcdef", "nonce-1", System.currentTimeMillis());

        String ct = crypto.encrypt(original, serverKey.getPublicKey());
        PaymentInstruction decrypted = crypto.decrypt(ct);

        assertEquals(original.getSenderVpa(), decrypted.getSenderVpa());
        assertEquals(original.getReceiverVpa(), decrypted.getReceiverVpa());
        assertEquals(0, original.getAmount().compareTo(decrypted.getAmount()));
        assertEquals(original.getNonce(), decrypted.getNonce());
    }

@Test
void sameNonceWithDifferentCiphertextCannotSettleTwice() throws Exception {
    BigDecimal aliceBefore =
            accounts.findById("alice@demo").orElseThrow().getBalance();
    BigDecimal bobBefore =
            accounts.findById("bob@demo").orElseThrow().getBalance();

    long signedAt = System.currentTimeMillis();

    // Two payment instructions with the same nonce but different ciphertexts.
    PaymentInstruction first = new PaymentInstruction(
            "alice@demo", "bob@demo", new BigDecimal("100.00"),
            "test-hash", "replay-test-nonce", signedAt);

    PaymentInstruction replay = new PaymentInstruction(
            "alice@demo", "bob@demo", new BigDecimal("100.00"),
            "test-hash", "replay-test-nonce", signedAt);

    MeshPacket packet1 = demoService.createPacket(
            "alice@demo", "bob@demo", new BigDecimal("100.00"), "1234", 5);

    // Encrypt both instructions independently: AES-GCM should produce
    // different ciphertexts for independently generated encryption parameters.
    String ciphertext1 = crypto.encrypt(first, serverKey.getPublicKey());
    String ciphertext2 = crypto.encrypt(replay, serverKey.getPublicKey());

    assertNotEquals(ciphertext1, ciphertext2);

    packet1.setCiphertext(ciphertext1);

    MeshPacket packet2 = demoService.createPacket(
            "alice@demo", "bob@demo", new BigDecimal("100.00"), "1234", 5);
    packet2.setCiphertext(ciphertext2);

    BridgeIngestionService.IngestResult result1 =
            bridge.ingest(packet1, "bridge-test-1", 1);
    BridgeIngestionService.IngestResult result2 =
            bridge.ingest(packet2, "bridge-test-2", 1);

    assertEquals("SETTLED", result1.outcome());
    assertNotEquals("SETTLED", result2.outcome(),
            "A payment nonce must not settle twice");

    BigDecimal aliceAfter =
            accounts.findById("alice@demo").orElseThrow().getBalance();
    BigDecimal bobAfter =
            accounts.findById("bob@demo").orElseThrow().getBalance();

    assertEquals(aliceBefore.subtract(new BigDecimal("100.00")), aliceAfter);
    assertEquals(bobBefore.add(new BigDecimal("100.00")), bobAfter);
}

}
