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
- **Item sets group themselves.** List armour piece by piece and it becomes one set - Torva
  helm, body and legs show as a single **Torva set**. Lending or selling one piece leaves the
  rest grouped, and it rejoins the set when it comes home.
- **Adding to a set:** right-click a listing → **Add to a set...** to put something with a kit
  that isn't part of it, like a Serpentine helm with Torva. You can take those extras back out;
  a kit's own pieces only leave by being lent or sold. You can also build a set from mixed gear,
  like a Bandos chest with Justiciar legs, and break that one up whenever you like.
- **Gear you're wearing** is hidden from the rest of the group until you take it off. You still
  see it, with a line through its icon.
- **Listings follow what you hold.** Once you've opened your bank, each listing shows what you
  actually have across your bank, inventory and worn gear: list 1 and hold 5, and 5 are offered;
  sell or lend 2 of 5, and it shows 3. Hold none and it's hidden from the group - you still see
  it, marked **Not in bank** in red (or **Out on loan** while it's lent out), and it comes back
  once you have it again. Nothing is ever deleted.
  To stop a listing following your bank (gear in your house, or you only want to lend some),
  right-click it → **Keep this amount**.

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

- Right-click a listing → **Request to Borrow**, choose how many (if there's more than one) and
  a duration, and agree to the terms. The lender sees it in their panel.
- Right-click a set → **Request whole set**, or **Request some items...** to tick just the
  pieces you want.
- **Looking For** posts what you need to the whole group, and shows who already has that item
  listed.

## More time on a loan

- **Borrowing:** right-click the loan → **Ask for more time...** and pick how many extra days.
  The lender can accept, say no, or offer a different date. You can accept that, ask for a
  different date once, or say no and return it on time. The lender's next answer is final.
- **Lending:** right-click the loan → **Give more time...** to move the due date later straight
  away, or to take the limit off.
- If the other person isn't in the group, there is nobody to ask, so whoever keeps the loan's
  record just sets the new date.
- A due date only ever moves later, never earlier.

## While you hold borrowed gear

The plugin warns you before you trade it away or take it into the Wilderness. Those settings
live under **Borrowed Item Guards**.

**Lookalike names.** If the player you're trading has a name that only looks like someone you
have a loan with, or someone in your group or clan, the trade window flashes red with both
names, e.g. "Guess 34" is NOT "Guess34". Swapped I, l and 1 or O and 0 count too. If you're
handing them something you borrowed from the real player, Accept is blocked; hold Shift only
if you're sure.

## Stale loans

An owner or co-owner can right-click someone else's loan → **Ask [lender] to remove this
loan...**. Nothing comes off until the lender approves it, because it's their item.

## Reminders

Overdue reminders cover your own loans only: in chat and as a popup when you log in, then once
a day, with optional sound. Lenders and borrowers both get them. Turn them off under
**Notifications**.

## Discord

If your staff have set up a channel:

1. Plugin settings → **Discord** → tick **Post loans to Discord**. RuneLite shows a warning,
   because loans go to an outside site.
2. You never paste a link — it comes with the group.
3. Optional: tick **Ping me in Discord** to be tagged in posts about your loans, and when someone
   is looking for an item you have listed. Your Discord ID is read from the Discord app if it is
   open on the same computer, or you can paste it.

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

Posted events: new loans, returns, incomplete returns, overdue loans, loans forgiven or
removed, due dates moved later, and requests for more time that were turned down. Each post
shows the players, items, values, collateral and a picture of the trade window.

**Looking For** posts go to the channel too, with the list of items and who in the group
already has them listed. Those players are tagged if they turned on **Ping me in Discord**.

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
- **More time** — borrowers ask for a later due date and lenders accept, say no or offer
  another date; lenders can also give more time straight away
- **Marketplace** — listings priced from the GE, armour grouped into sets automatically, gear
  categories worked out from each item's own stats (melee, range, mage, tank, DPS, stab, slash,
  crush, end game), search and filters with live counts, sets listed above single items, gear
  you're wearing hidden until you take it off, listings that follow what you hold, Looking For
  board, borrow requests and lend offers
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
- It also tells your group which of the items **you have listed** you are currently wearing, so
  they aren't offered gear you're using. Nothing you haven't listed is ever shared.
- Once you've opened your bank, each of your listings shows the group how many of that item you
  hold across your bank, inventory and worn gear. Only items you've listed are counted - the rest
  of your bank never leaves your computer. Pick **Keep this amount** on a listing to show a fixed
  number instead.
- Discord posting is off by default. When on, loans — names, items, values, due dates and a
  picture cropped to the trade window — are posted to the channel your staff set up, and so are
  your Looking For posts (the items, your note, and which members have them listed). Your chat
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
