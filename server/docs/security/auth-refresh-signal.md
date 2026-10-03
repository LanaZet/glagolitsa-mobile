# Auth Refresh And Signal Device Boundaries

This project treats HTTP auth sessions and Signal-style device state as separate security
domains.

## Auth session

- `access_token` is a short-lived bearer token for API transport.
- `refresh_token` is a long-lived session secret for the same logged-in device. The server
  stores only its hash.
- Refresh keeps the device-bound session secret stable and issues a new short-lived
  `access_token`. The refresh secret is rotated only on explicit re-login, logout,
  revocation, recovery, or future high-risk server policy.
- Refresh failure must not delete local encrypted history or cryptographic device state.

## Signal-style device state

- `DeviceID` identifies a logical linked device.
- The device identity key, signed prekey, one-time prekeys, mailbox routing, and local
  ratchet/session records belong to the Signal-style device state.
- This state changes only on explicit device registration, confirmation, revocation,
  unlink/relink, reinstall, or account recovery.
- A refresh-token failure is not an unlink event and must not rotate `DeviceID` or identity
  material.

## Required behavior

1. Login may create or reuse an auth session for a known `DeviceID`.
2. Refresh may rotate only `access_token`; it must not rotate the Signal-style
   `DeviceID`, device identity, or the device-bound refresh credential during normal
   background refresh.
3. Refresh with a valid legacy session and a client `DeviceID` may bind the new auth session
   to that existing device, but must not create a new device.
4. Refresh with a mismatched non-empty `DeviceID` must fail.
5. Repeated refresh with the same valid refresh secret and same `DeviceID` must be idempotent
   enough for mobile retries, concurrent jobs, process restarts, and flaky networks.
6. The mobile client may suspend online work when refresh fails, but it must keep the local
   authenticated shell, cached user, local history, `DeviceID`, and Signal identity intact.

These rules follow the same separation used by Signal/Sesame: device identity and message
ratchet state are not transport bearer-token lifecycle state.

## Split-secret recovery vault

- Recovery should be modeled as two shards: a local shard stays on the user device, and a
  cloud shard is stored only as ciphertext in the recovery vault.
- Passkeys, trusted devices, and time-locks are additive gates around the shards, not a
  replacement for them.
- The current client UI exposes this as a concept screen in Profile/Settings so the recovery
  story stays visible next to the existing Secure History flow.
- Server-side recovery should never downgrade to a password-reset style support flow; the
  vault must remain opaque to operators.
