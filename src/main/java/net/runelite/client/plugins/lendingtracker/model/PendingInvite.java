package net.runelite.client.plugins.lendingtracker.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PendingInvite {
    private String playerName;
    private String codeUsed; // The code they entered
    private String codeType; // "group" or "clan"
    private long requestDate;

    public PendingInvite(String playerName, String codeUsed, String codeType) {
        this.playerName = playerName;
        this.codeUsed = codeUsed;
        this.codeType = codeType;
        this.requestDate = System.currentTimeMillis();
    }
}
