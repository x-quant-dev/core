# FX Credit Engine

This module implements a low-latency **FX Credit Engine** using **Design 3 (Atomic Broadcast / Total Order via Sequencer)**. All credit limits, consumption updates, and hard stops are sequenced through a central ordering service to guarantee absolute, replica-identical consistency without request/reply round-trip compounding on the critical trading path.

---

## 1. Quick Start

### Build the Application
Before running, build the test suite and package the executable UberJAR:
```bash
# Generate schema classes, compile Java, and run the test suite
./gradlew :credit:test

# Build the standalone UberJAR containing all dependencies
./gradlew :credit:uberjar
```

### Run the Standalone VM
Boot the credit engine using the platform's VM launcher and load the `credit-engine.cmd` script to wire up the sequencer, passive replica state, CLI, and HTTP listeners:
```bash
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:credit/src/main/resources \
  -jar credit/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main -s credit-engine.cmd
```

---

## 2. Interactive Testing via Command Line (CLI)

The command file exposes an interactive shell on port `7001`. You can connect using netcat (`nc`) or telnet:
```bash
nc 0.0.0.0 7001
```

Once connected, you can run the following injector commands to trigger sequencer-side decisions and update state.

> [!IMPORTANT]  
> All monetary amounts are represented as **USD cents** (long) to prevent floating-point inaccuracies (e.g. `$1,000,000.00` is `100000000`).

### 1. Load Start of Day (SOD) Consumed Exposures
Seed the sequencer and replica state with carried-forward exposures:
```txt
/ % inject01a/loadSodConsumed accountId=ACCT1 consumedUsd=1500000 limitUsd=100000000
```

### 2. Request a Credit Check
Check if a new order exposure fits within the remaining credit limit:
```txt
# Buy (side=0) SPOT EUR/USD order for $50,000.00 (5,000,000 cents)
/ % inject01a/creditCheckRequest orderId=ORD1 accountId=ACCT1 clientId=CLIENT1 currencyPair=EURUSD productType=SPOT notionalUsd=5000000 side=0
```
If the check is successful, the sequencer publishes `CreditAccepted` and increases the account's consumed exposure. If it breaches, it publishes `CreditRejected` with code `REJECT_LIMIT_EXCEEDED`.

### 3. Change Credit Limit Manually
Dynamically adjust the master limit:
```txt
/ % inject01a/setCreditLimit accountId=ACCT1 limitUsd=200000000
```

### 4. Trigger an Emergency Hard Stop
Immediately reject all incoming orders for an account, regardless of available credit limit:
```txt
# Apply hard stop
/ % inject01a/hardStop accountId=ACCT1 reason="Regulatory breach threshold"

# Attempt a check (will be REJECT_HARD_STOP)
/ % inject01a/creditCheckRequest orderId=ORD2 accountId=ACCT1 clientId=CLIENT1 currencyPair=EURUSD productType=SPOT notionalUsd=10000 side=0

# Release the hard stop
/ % inject01a/releaseHardStop accountId=ACCT1
```

---

## 3. Web & Browser Testing via HTTP (HttpShell)

The `credit-engine.cmd` script starts an HTTP shell listener on port `8001`. You can interact with it using curl, Postman, or any web browser.

### Inspecting State (GET Requests)

You can query current objects and directories directly in the browser.

* **List available components:**
  ```bash
  curl http://localhost:8001/
  ```
* **Inspect the Credit Store replica (consumed exposures / limits):**
  ```bash
  curl http://localhost:8001/creditStore
  ```
* **Check Sequencer Handlers state:**
  ```bash
  curl http://localhost:8001/seq01a/creditHandlers
  ```

---

### Executing Commands (POST Requests)

Trigger action methods by sending `POST` requests. Arguments are supplied in standard URL-encoded form data (`application/x-www-form-urlencoded`).

#### 1. Load Start of Day (SOD) State
```bash
curl -X POST http://localhost:8001/inject01a/loadSodConsumed \
     -d "accountId=ACCT1&consumedUsd=1500000&limitUsd=100000000"
```

#### 2. Request a Credit Check
```bash
curl -X POST http://localhost:8001/inject01a/creditCheckRequest \
     -d "orderId=ORD1&accountId=ACCT1&clientId=CLIENT1&currencyPair=EURUSD&productType=SPOT&notionalUsd=5000000&side=0"
```

#### 3. Update Credit Limit
```bash
curl -X POST http://localhost:8001/inject01a/setCreditLimit \
     -d "accountId=ACCT1&limitUsd=200000000"
```

#### 4. Trigger Hard Stop
```bash
curl -X POST http://localhost:8001/inject01a/hardStop \
     -d "accountId=ACCT1&reason=ManualRiskReview"
```

#### 5. Release Hard Stop
```bash
curl -X POST http://localhost:8001/inject01a/releaseHardStop \
     -d "accountId=ACCT1"
```

---

## 4. Architecture Reference

All components adhere to the core platform's zero-allocation, deterministic execution model:

```
  CLI / HTTP / REST   ─────────► [ Sequencer Command Channel ]
                                            │
                                            ▼
                              [ Sequencer / MoldBusServer ]
                              Evaluates: CreditState.java
                                            │
                                            ├────────► (Publishes ordered events to MoldBusClient)
                                            ▼
                              [ Passive Replica / Client State ]
                              Materializes state for low-latency queries
```

* **Deterministic Domain State:** All calculations inside [CreditState.java](file:///Users/nader/Projects/github/x-quant-dev/core/credit/src/main/java/com/core/credit/domain/CreditState.java) are completely garbage-collection free and deterministic.
* **Passive Replica:** The client application ([CreditStoreClient.java](file:///Users/nader/Projects/github/x-quant-dev/core/credit/src/main/java/com/core/credit/applications/CreditStoreClient.java)) materializes state directly from the sequential event channel, allowing sub-microsecond local queries (`creditStore/getConsumed` / `creditStore/getLimit`) on the trading hot path.
