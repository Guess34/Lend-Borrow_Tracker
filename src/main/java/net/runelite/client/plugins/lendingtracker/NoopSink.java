package net.runelite.client.plugins.lendingtracker;

final class NoopSink implements EventSink
{
    @Override
    public void push(TradeRecord rec)
    {
        // no-op
    }
}