# Lending Tracker

A [RuneLite](https://runelite.net/) plugin for clans and friend groups who lend gear to each
other. It records who lent what to whom, what collateral was put up, when it is due back, and
whether it came home — straight from the trade window.

Only one side of a trade needs the plugin, so you can lend to clan mates on mobile or in
vanilla and still have a record of it.

## Quick start

**Members**

1. Install the plugin and open its panel from the RuneLite sidebar.
2. Plugin settings → **Sync** → turn on **Enable Cloud Sync**. Without it, nothing reaches the
   rest of your group.
3. Get a **group code** from your staff and join with it on the **Groups** tab.
4. Offer gear with **Offer Item**, or right-click an item in your inventory → **Add to Lending
   List**.
5. In a trade: the **lender** taps **Loan**, the **borrower** taps **Collat**.

**Staff**

1. Turn on Cloud Sync, then create your group on the **Groups** tab.
2. Share a code from group settings, and set which ranks may kick and invite.
3. Optional: connect a **Discord** channel and link the group to your **in-game clan**.

---

# Member guide

## Inviting someone in game

Right-click a player → **Invite to Lending Group** and the plugin puts an invite message on
your clipboard, ready to paste in chat with Ctrl+V. You can turn that menu entry off in the
plugin's settings.

## Joining a group

Paste the code your staff gave you on the **Groups** tab. You then see the group's
marketplace, its members, and your own loans. Cloud Sync must be on.

## Offering gear

- **Offer Item** on the dashboard, or right-click an item in your inventory → **Add to Lending
  List**.
- Set quantity, value and any collateral you want for it. Values come from the GE and keep
  themselves up to date.
- **Item sets:** right-click one of your listings → **Add to a set...** to group pieces
  together, like a full Inquisitor's. Others can request the whole set, lending one piece
  leaves the rest listed, and the piece rejoins the set when it comes home.

## Lending and borrowing in a trade

The plugin adds its own buttons to the trade window:

| Button | What it means |
|---|---|
| **Loan** | You are the lender. Everything you hand over is recorded as a loan |
| **Collat** | You are the borrower. Everything you hand over is your collateral |
| **Days** | How long the loan runs: 1 to 7 days, or No limit |

Only the lender's plugin records the loan, so it is never counted twice. If the lender has no
plugin — a clan mate on mobile, say — tap **Collat** and your own client keeps the record
instead.

A proof screenshot of the trade window is saved automatically, on your own computer.

## Getting gear back

- Trade the items back and the loan settles itself.
- A loan is only finished once the items **and** the collateral are back. Either can come in a
  later trade, including one where the other person offers nothing.
- If part is still out, the plugin tells you exactly what is missing and who has it.

## Asking to borrow

- Right-click a listing → **Request to Borrow**, choose a duration and agree to the terms. The
  lender sees it in their panel.
- **Looking For** posts what you need to the whole group, and shows who already has that item
  listed.

## While you hold borrowed gear

The plugin warns you before you trade it away or take it into the Wilderness. Those settings
live under **Borrowed Item Guards**.

## Reminders

Overdue reminders cover your own loans only, once a day, with optional sound. Turn them off
under **Notifications**.

## Discord

If your staff have set up a channel:

1. Plugin settings → **Discord** → tick **Post loans to Discord**. RuneLite shows a warning,
   because loans go to an outside site.
2. You never paste a link — it comes with the group.
3. Optional: tick **Ping me in Discord** to be tagged in posts about your loans. Your Discord
   ID is read from the Discord app if it is open on the same computer, or you can paste it.

---

# Staff guide

## Letting people in

| Code | Use |
|---|---|
| **Invite code** | One person, one use |
| **Group code** | Stays open for a clan, can be a custom phrase, and joins can be closed any time |

Changing a code kills the old one immediately, which is how you shut the door after a kick.
Codes expire on the server after 24 hours unless a staff member is online to keep them alive.

## Ranks and permissions

Ranks run **owner → co-owner → admin → mod → member**. In group settings you choose which
ranks may **kick** and which may **generate invite codes**. Owners and co-owners handle
everything else.

## Kicking

A kick sticks: the member loses the group on every computer they use, and old codes cannot
bring them back. Loan records stay with both sides, so anything still open remains on the
dashboard. Rotate the group code afterwards if they left on bad terms.

## Deleting a group

Only an owner or the founder can, and it removes the group **for every member**. It is blocked
while anything at all is still on loan. If the server does not confirm the delete, the group
stays on your side so you can press Delete again — nobody is left holding a group that cannot
be cleared.

## Discord channel

1. In Discord: Edit Channel → Integrations → Webhooks → Copy Webhook URL.
2. Group settings → **Discord** → **Set up** → paste it. It is stored locked with the group's
   key and synced to every member, so **members never paste anything**.
3. **Test** sends one message to check the channel, then locks until the webhook changes.
4. Each member ticks **Post loans to Discord** once in their own settings.

Only co-owners and owners can set or change it. The posts' name and picture come from the
webhook, so set those in Discord.

Posted events: new loans, returns, incomplete returns, overdue loans, and loans forgiven or
removed. Each post shows the players, items, values, collateral and a picture of the trade
window.

## Linking an in-game clan

Only for groups that belong to a clan. In the same **Set up** window, tick **This group
belongs to an in-game clan**, then press **Use my clan** or type the name. It does not have to
match the group's name. The plugin reads the game's own clan list, so clan mates on mobile
count as members even though they cannot run the plugin.

Leave it off for a group of friends. Nothing clan-related is then used, and the group can have
its own Discord channel in its own server.

**Track loans with** decides whose loans the group records and posts:

| Setting | Meaning |
|---|---|
| **Clan members only** | Both players must be in the linked clan |
| **Group members only** | Both players must be in this group |
| **Anyone** | Every loan made in the group |

"Clan members only" is offered once the group has a clan. A trade outside that setting is not recorded, and the player is told why in chat.

## Proof

Every loan and return trade saves a screenshot on the lender's computer. Group settings →
**Show Screenshot Folder** gives the path.

---

## Everything it does

- **Groups** — roles and per-role permissions, ownership transfer, founder handover, kicks that
  stick across computers, and group deletion that clears for everyone
- **Loans from the trade window** — items, collateral in gear or GP, value, due date, partial
  returns tracked piece by piece, and proof screenshots
- **Marketplace** — listings priced from the GE, item sets, gear categories worked out from
  each item's own stats (melee, range, mage, tank, DPS, stab, slash, crush, end game), search
  and filters, listings grouped by owner, Looking For board, borrow requests and lend offers
- **Clan and Discord** — link a group to an in-game clan, choose whose loans it tracks, and
  post loan activity to a Discord channel with optional pings
- **Roster** — every member with their rank, who is online, and the world they are on
- **Reminders and history** — daily overdue reminders for your own loans, completed loan
  history with status badges, per-account storage and local backups
- **Sync** — group data shared between members' computers through a relay server, signed with a
  per-group key, catching up automatically after you reconnect

## Privacy

- Cloud Sync sends your player name, world and online status to the relay server so your group
  can see who is online. RuneLite shows this warning when you turn it on.
- Discord posting is off by default. When on, loans — names, items, values, due dates and a
  picture cropped to the trade window — are posted to the channel your staff set up. Your chat
  and inventory are never included.
- Proof screenshots stay on your own computer, under `~/.runelite/lending-tracker/proof/`.

## Installation

1. Open RuneLite
2. Go to the Plugin Hub (wrench icon in the sidebar)
3. Search for **Lending Tracker**
4. Click **Install**

## Building from source

```bash
./gradlew build
```

## License

BSD 2-Clause. See [LICENSE](LICENSE) for details.
