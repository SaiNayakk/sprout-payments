# sprout-payments

Money between a customer's Sprout Bank account and their Sprout account. Payments keeps each payment's
story; the [ledger](https://github.com/SaiNayakk/sprout-ledger) keeps the money.

**Adding money** is a UPI collect request. The customer approves it in Sprout Bank with their PIN; the
bank's signed callback credits their Sprout cash.

**Withdrawing** holds the money in the ledger first (so it can't be spent twice), asks the bank to pay
it out, then settles the hold, or releases it if the bank refuses.

Rules that keep money from being lost or made up:

- Every ledger entry and bank instruction carries the payment's id as its idempotency key or reference, so any step can be repeated safely; every `POST` needs an `Idempotency-Key`.
- **The bank's approval is the truth.** An approved collect is credited even if the deposit had been marked failed (e.g. the bank timed out answering us but created the request).
- **Callbacks** are checked against the bank's signature, applied once per event, and not marked applied if the ledger was down, so the bank's retry applies them later.
- **Unknown outcomes are never guessed.** A withdrawal whose payout or settlement got no answer stays `PROCESSING`; the reconciler retries the idempotent steps until there's an answer. It also asks the bank about deposits whose callback never came.

The tests drive every one of these paths against stand-ins for accounts, the ledger and the bank that can refuse or disappear.

## Part of Sprout

[Sprout](https://sainayakk.github.io/sprout-platform/) is a simulated brokerage built from scratch as
separate services, each with its own repository and contract. Architecture, environments and test
evidence live in [sprout-platform](https://github.com/SaiNayakk/sprout-platform); this service's API is
[`payments-v1.yaml`](https://github.com/SaiNayakk/sprout-contracts/blob/main/src/main/resources/sprout/contracts/openapi/payments-v1.yaml)
in sprout-contracts. It runs inside the **money** host.

`./mvnw verify` runs the tests on a real Postgres (Docker needed), every JSON response checked against
the contract.

## License

MIT
