# Assignment: Mini Wallet Service

## 1. Overview

Build a backend service that manages digital wallets and money movement, a simplified version of what we do at **Pathao
Pay**. The focus is correctness, safety of money operations, and clean engineering, not the number of features.

## 2. Effort

A smaller, correct, well-tested solution is better than a large, buggy one.

## 3. Tech Stack

* Language/framework: Java/Spring Boot.
* Database: PostgreSQL
* Setup: The whole system must start with a single command: `docker compose up`.

## 4. Functional Requirements

### 4.1 Money rules (apply everywhere)

* Currency is BDT only.
* Amounts are sent and stored as integers in poisha (1 BDT = 100 poisha). Floating point must not be used for money.
* Minimum amount per add money or transfer: 100 poisha (1 BDT).
* Maximum amount per single transaction: 2,500,000 poisha (25,000 BDT).
* A wallet balance can never go negative.

### 4.2 Wallet management

#### Create wallet

```http
POST /api/v1/wallets
```

```json
{
  "user_id": "u_1001",
  "phone": "01711000001"
}
```

Response 201:

```json
{
  "wallet_id": "w_8f3a...",
  "user_id": "u_1001",
  "currency": "BDT",
  "balance": 0,
  "created_at": "2026-11-05T10:00:00Z"
}
```

One user can have only one wallet. A duplicate request returns 409.

#### Get wallet

`GET /api/v1/wallets/{wallet_id}` returns the wallet with its current balance.

### 4.3 Add money

```http
POST /api/v1/wallets/{wallet_id}/add-money
Header: Idempotency-Key: <unique string>
```

```json
{
  "amount": 50000,
  "gateway_ref": "GW-20261105-9981"
}
```

This simulates a payment gateway confirming a payment. On success, the balance increases and a transaction record is
created. `gateway_ref` must be unique, so the same gateway reference can never credit a wallet twice.

### 4.4 P2P transfer

```http
POST /api/v1/transfers
Header: Idempotency-Key: <unique string>
```

```json
{
  "from_wallet_id": "w_aaa",
  "to_wallet_id": "w_bbb",
  "amount": 10000,
  "note": "Lunch"
}
```

Response 201:

```json
{
  "transfer_id": "t_123",
  "from_wallet_id": "w_aaa",
  "to_wallet_id": "w_bbb",
  "amount": 10000,
  "status": "COMPLETED",
  "created_at": "2026-11-05T10:05:00Z"
}
```

Rules:

* The debit and credit must happen atomically. There must never be a state where one happened and the other did not.
* The sender needs sufficient balance.
* Sender and receiver must be different wallets.
* Both wallets must exist.

### 4.5 Transaction history

```http
GET /api/v1/wallets/{wallet_id}/transactions?type=&from=&to=&page=&page_size=
```

* `type`: `ADD_MONEY`, `TRANSFER_OUT`, `TRANSFER_IN` (optional)
* `from` / `to`: ISO-8601 date range (optional)
* Paginated (default page size 20, max 100), sorted newest first
* Each entry shows type, amount, balance after the transaction, counterparty (if any), reference ID, and timestamp.

### 4.6 Idempotency

Both add-money and transfers must support the `Idempotency-Key` header:

* Same key and same request body: do not process again. Return the original response (same status code and body).
* Same key but a different request body: return 422 with an appropriate error code.
* Two requests with the same key arriving at the same time must result in exactly one execution.
* Missing header: return 400.

### 4.7 Error format

Use a consistent error response for all failures:

```json
{
  "error": {
    "code": "INSUFFICIENT_BALANCE",
    "message": "Wallet does not have enough balance for this transfer."
  }
}
```

At minimum, handle: `INSUFFICIENT_BALANCE`, `WALLET_NOT_FOUND`, `INVALID_AMOUNT`, `SAME_WALLET_TRANSFER`,
`DUPLICATE_WALLET`, `DUPLICATE_GATEWAY_REF`, `IDEMPOTENCY_KEY_REUSED`, and validation errors. Use sensible HTTP status
codes.

## 5. Finally

Include a `README.md` in the repository root with:

* The one-command setup to run the project (`docker compose up`)
* How to run the tests
* Any assumptions you made
