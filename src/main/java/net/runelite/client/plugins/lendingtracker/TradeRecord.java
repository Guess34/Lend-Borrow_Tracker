package net.runelite.client.plugins.lendingtracker;

import java.time.Instant;

class TradeRecord
{
    String tradeId;
    String partner;
    String note;
    LendingTrackerConfig.Role role;
    int world;
    Instant at;

    TradeRecord() {}

    TradeRecord(String tradeId, String partner, LendingTrackerConfig.Role role, String note, int world, Instant at)
    {
        this.tradeId = tradeId;
        this.partner = partner;
        this.role = role;
        this.note = note;
        this.world = world;
        this.at = at;
    }

    @Override
    public String toString()
    {
        String r = role == null ? "NONE" : role.name();
        return at + "  w" + world + "  " + r + "  " + (partner == null ? "?" : partner) +
               (note != null && !note.isBlank() ? "  — " + note : "");
    }
}
