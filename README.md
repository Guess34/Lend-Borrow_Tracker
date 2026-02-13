# Lending Tracker

A [RuneLite](https://runelite.net/) plugin that enables Old School RuneScape players to form groups and track item lending and borrowing between members.

## Features

- **Group System** — Create or join lending groups with role-based permissions (Owner, Co-Owner, Admin, Mod, Member)
- **Peer-to-Peer Marketplace** — List items as available for lending; browse what group members are offering
- **Item Set Bundles** — Bundle multiple items (e.g., full armor sets) into a single lendable package with preset templates
- **Automated Trade Detection** — Detects in-game trades with group members and automatically records lending/borrowing
- **Two-Party Return Confirmation** — Both lender and borrower independently confirm item returns
- **Risk Analysis** — Player risk scoring based on lending history and behavior
- **Collateral Tracking** — Record collateral agreements alongside loans
- **Discord Webhook Integration** — Get notifications in your Discord server for lending activity
- **Screenshot Proof** — Automatic screenshot capture during trades for record-keeping
- **In-Game Overlays** — Safety warnings near the Wilderness, Grand Exchange, and when dropping borrowed items
- **Borrow Requests** — Post "looking for" requests so group members know what you need
- **Overdue Loan Alerts** — Desktop and in-game notifications when loans pass their due date
- **Configurable Permissions** — Control who can kick members, generate invite codes, and manage roles

## Installation

1. Open RuneLite
2. Go to the Plugin Hub (wrench icon in the sidebar)
3. Search for **Lending Tracker**
4. Click **Install**

## Usage

1. Open the Lending Tracker panel from the sidebar
2. Create a new group or join an existing one using an invite code
3. Right-click items in your inventory to list them on the marketplace
4. Group members can browse available items and request to borrow
5. Complete trades in-game — the plugin tracks everything automatically

## Building from Source

```bash
./gradlew build
```

To run in developer mode:

```bash
./gradlew run
```

## License

BSD 2-Clause. See [LICENSE](LICENSE) for details.
