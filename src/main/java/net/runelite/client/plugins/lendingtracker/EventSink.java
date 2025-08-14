package net.runelite.client.plugins.lendingtracker;

interface EventSink
{
    void onTradeCompleted(TradeRecord rec);
}
