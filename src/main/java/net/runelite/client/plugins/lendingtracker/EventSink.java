package net.runelite.client.plugins.lendingtracker;

interface EventSink
{
    void push(TradeRecord rec);
}