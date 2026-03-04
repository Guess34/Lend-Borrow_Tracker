# Lending Tracker

A [RuneLite](https://runelite.net/) plugin that enables Old School RuneScape players to form groups and track item lending and borrowing between members.

## Features

### Group System
- Create or join lending groups with role-based permissions (Owner, Co-Owner, Admin, Mod, Member)
- Single-use invite codes with shareable format (ABC-123-XYZ)
- Right-click players in-game to generate an invite message (copies to clipboard — paste in-game with Ctrl+V)
- Ownership transfer, member kicking, and role management
- Configurable permissions per role (who can kick, invite, manage)

### Cross-Machine Sync
- Group data syncs in real-time between all members on different computers
- Secure relay server with HMAC-SHA256 signed messages
- Automatic reconnection with exponential backoff
- Connection status indicator in the panel header

### Marketplace
- Right-click items in your inventory to list them for lending
- Browse what group members are offering on the Dashboard tab
- Set collateral type, value, percentage, duration, and notes per listing
- GE price integration with automatic price updates
- Looking For board — post requests for items you need so group members know what to lend

### Tracking & History
- Lending history with status badges (Returned, Overdue, Active, Defaulted)
- Overdue loan alerts with configurable reminder frequency
- Desktop and in-game sound notifications when loans pass their due date
- Wilderness warning when entering the Wilderness with borrowed items
- Screenshot proof capture for trade record-keeping

### Group Roster
- See all group members with their roles
- Online status detection (via friends list and Friends Chat)
- World number display for online members

### Data & Storage
- Per-account data storage — each OSRS account has its own groups and data
- Local JSON backup and restore
- Automatic save on shutdown, auto-load on login

### Coming Soon
- **Borrow Requests** — Request to borrow specific items directly from group members
- **Item Set Bundles** — Bundle multiple items into a single lendable package
- **Risk Analysis** — Player risk scoring based on lending history and behavior
- **Discord Integration** — Get notifications in your Discord server for lending activity

## Installation

1. Open RuneLite
2. Go to the Plugin Hub (wrench icon in the sidebar)
3. Search for **Lending Tracker**
4. Click **Install**

## Usage

1. Open the Lending Tracker panel from the sidebar
2. Create a new group or join an existing one using an invite code
3. Right-click items in your inventory to list them on the marketplace
4. Group members can browse available items on the Dashboard tab
5. Use the Roster tab to see group members and their online status
6. Check the History tab for completed lending transactions

## Building from Source

```bash
./gradlew build
```

## License

BSD 2-Clause. See [LICENSE](LICENSE) for details.
