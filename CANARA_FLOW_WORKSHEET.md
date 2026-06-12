# Canara Bank `*99#` Flow Mapping Worksheet (Step 2)

The automation engine matches against the **exact** text Canara/your telecom returns.
These vary by bank, telecom, and over time, so you must capture the real screens once.

## How to capture

1. On the target SIM, manually dial `*99#`.
2. At each screen, write down the **verbatim** prompt text and the option you pick.
3. Fill the table below, then update `CanaraFlow.kt` (`classify()` keywords and
   `inputFor()` option numbers) to match.

## Capture table

| # | Verbatim screen text | Your input | Maps to `CanaraState` |
|---|----------------------|-----------|------------------------|
| 1 | (dial)               | `*99#`    | START                  |
| 2 |                      |           | LANGUAGE               |
| 3 |                      |           | MAIN_MENU              |
| 4 |                      |           | SEND_VIA               |
| 5 |                      |           | ENTER_VPA              |
| 6 |                      |           | ENTER_AMOUNT           |
| 7 |                      |           | ENTER_REMARKS          |
| 8 |                      | (PIN)     | ENTER_PIN              |
| 9 |                      | —         | CONFIRMATION           |

## Things to double-check

- The **option numbers** for "Send Money" and "pay to UPI ID" — these differ per bank.
- Whether a **"select bank / account"** screen appears (add a state if so).
- The exact **confirmation SMS** wording — tune the regexes in `SmsConfirmationReceiver.kt`.
- Whether your telecom needs 2G/3G (USSD does not work on VoLTE-only / Jio in many cases).
