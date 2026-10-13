# UPI Offline Mesh 

A project exploring how payment requests could be relayed between nearby devices when internet connectivity is unavailable.

The idea is simple: a payment request is encrypted, passed between devices through a simulated mesh network, and forwarded to a backend when a device gets internet access. The backend then validates the request and processes the simulated payment.

**Note:** This is an educational project, not a real UPI application. It uses simulated devices, accounts, and payments.

##  Features

- **Hybrid encryption:** RSA-OAEP and AES-GCM to protect payment data.
- **Mesh simulation:** Simulates payment packets travelling between virtual devices.
- **Duplicate detection:** Prevents identical packets from being processed repeatedly.
- **Replay protection:** Uses payment nonces and database uniqueness constraints to help prevent repeated payment attempts.
- **Transaction handling:** Updates sender and receiver balances within a database transaction.
- **Interactive dashboard:** View account balances, mesh activity, and transaction history.
- **Automated tests:** Tests encryption, tampered packets, duplicate delivery, and repeated payment nonces.

##  Tech Stack

- Java 17
- Spring Boot
- Spring Data JPA
- H2 Database
- Maven
- RSA-OAEP and AES-GCM
- JUnit 5

##  How It Works

1. A payment instruction is created with sender, receiver, amount, nonce, and timestamp.
2. The instruction is encrypted and wrapped in a mesh packet.
3. Virtual devices relay the packet through simulated gossip rounds.
4. A simulated bridge device forwards the packet to the backend.
5. The backend checks for duplicate packets, decrypts the request, and validates its freshness.
6. The settlement service attempts the debit and credit and records the transaction.

##  Getting Started

**Prerequisite:** JDK 17 or newer.

Clone the repository:

```bash
git clone https://github.com/perryvegehan/UPI_Without_Internet.git
cd UPI_Without_Internet
```

Run the application on macOS/Linux:

```bash
chmod +x mvnw
./mvnw spring-boot:run
```

Open **http://localhost:8080** to access the dashboard.

To run the tests:

```bash
./mvnw test
```

The current test suite contains four tests covering encryption/decryption, tampered ciphertext, concurrent delivery of the same packet, and reuse of a payment nonce with different ciphertexts.

##  Security Notes

This project helped me explore encryption, idempotency, replay protection, and transactional updates in a backend system.

It is still a prototype. The mesh is simulated, payments are not connected to real banks, and concurrent nonce conflicts need further verification. It also does not implement real UPI PIN verification or production-grade sender authentication.

##  Future Improvements

- Test concurrent requests with the same payment nonce.
- Improve database conflict handling and transaction rollback tests.
- Add authenticated bridge requests.
- Explore real device-to-device communication.
- Add automated testing through GitHub Actions.

##  What I'm Learning

Building and extending this project has helped me understand backend development with Spring Boot, hybrid encryption, database transactions, concurrency, and the challenges of designing reliable offline-first systems.
