# Lending Tracker (RuneLite Plugin)

> **Status: MVP**  
> Core logging is implemented. Item list parsing, in-client history, and Discord embeds are **planned** (tracked in Roadmap). No ETA is promised.

A simple RuneLite plugin that records clan lending/borrowing trades. Works completely offline; optional webhook lets you send events to your own bot/server.

---

## What it does (MVP)
- Detects a **completed trade** and lets you mark it: **Lend / Borrow / Not a loan**.
- Logs the event (time, world, partner, role and optional note).
- Shows a **Lending Tracker** sidebar with your most recent records.
- **Optional:** POST the event as JSON to your webhook/URL.
- **Optional:** Add an HMAC “tamper-seal” to each POST if you enter a secret (see Webhook).

## Not included yet (planned)
- Reading/parsing the **item list** (IDs, names, quantities).
- In-client history browser beyond the recent list.
- Discord embeds with item breakdowns.

See **Roadmap** below.

---

## Storage location
- **Windows:** `%USERPROFILE%\.runelite\lendingtracker\trades.json`  
  _Example:_ `C:\Users\Bob\.runelite\lendingtracker\trades.json`
- **Linux / macOS:** `$HOME/.runelite/lendingtracker/trades.json`  
  _Examples:_ `/home/alex/.runelite/lendingtracker/trades.json`, `/Users/Sam/.runelite/lendingtracker/trades.json`

---

## How to use
1. Install from Plugin Hub (once approved) and open **Configuration → Lending Tracker**.
2. (Optional) Enable **Prompt for role on trade** to choose Lend/Borrow each time.
3. Trade as normal. When the trade completes, you’ll see a confirmation and a new entry in the panel.

---

## Webhook (optional)
If you want to send events to your own Discord bot or any HTTP endpoint:

1. Enable **Webhook** in settings and paste your **Webhook URL**.
2. (Optional) Enter an **HMAC secret**. If set, the plugin adds a signature so your server can verify the message is real.

**Payload example**
```json
{
  "type": "trade_completed",
  "record": {
    "tradeId": "uuid-here",
    "partner": "Alice",
    "role": "LENDER",
    "note": "agreed return tonight",
    "world": 345,
    "at": "2025-08-14T19:22:03Z"
  },
  "signature": "hmac-sha256:..." // present only if HMAC secret is set
}
## Roadmap

Below is a list of planned features and improvements. These are **planned**, not promised by a certain date.

### Short-Term (Next Updates)
- **Item list parsing** – Capture exact items, quantities, and noted status from the second confirm screen.
- **Improved event viewer** – Browse full trade history inside RuneLite, with search and filters.
- **Config help tooltips** – More beginner-friendly descriptions in settings.

### Mid-Term
- **Discord embeds** – Richly formatted webhook messages showing items lent/borrowed.
- **Export / import** – Save and load your trade history to share with other devices or clanmates.
- **Config profiles** – Save multiple configuration sets for different accounts or uses.

### Long-Term / Ideas
- **Cloud sync** – Optional sync of trade history across multiple PCs.
- **Statistics view** – Summaries of lending frequency, most-traded items, etc.
- **Custom alerts** – Trigger notifications based on trade partner or item.

---
