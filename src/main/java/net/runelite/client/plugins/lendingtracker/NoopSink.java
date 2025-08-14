package net.runelite.client.plugins.lendingtracker;

class NoopSink implements EventSink
{
    @Override public void onTradeCompleted(TradeRecord rec) { /* no-op */ }
}
