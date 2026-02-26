package com.guess34.lendingtracker;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.eventbus.EventBus;

import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.Notifier;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import com.guess34.lendingtracker.model.*;
import com.guess34.lendingtracker.ui.LendingPanel;
import com.guess34.lendingtracker.services.DataService;
import com.guess34.lendingtracker.services.LocalDataSyncService;
import com.guess34.lendingtracker.services.ProofScreenshot;
import com.guess34.lendingtracker.services.GroupService;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@PluginDescriptor(
	name = "Lending Tracker",
	description = "Track items lent to other players with advanced features",
	tags = {"lending", "items", "tracker", "party", "group", "risk"}
)
// This plugin has been trimmed more times than rune armor at the GE. ~Guess34
public class LendingTrackerPlugin extends Plugin
{
	private static final int LENDING_WIDGET_GROUP = 334;
	private static final int ACCEPT_BUTTON = 13;

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ConfigManager configManager;
	@Inject private LendingTrackerConfig config;
	@Inject private ClientToolbar clientToolbar;
	@Inject private ItemManager itemManager;
	@Inject private ScheduledExecutorService executor;
	@Inject private Notifier notifier;
	@Inject private EventBus eventBus;
	@Inject private DataService dataService;
	@Inject private GroupService groupService;
	@Inject private LocalDataSyncService localDataSyncService;
	@Inject private ProofScreenshot proofScreenshot;

	private LendingPanel newPanel;
	private NavigationButton navButton;
	private volatile LendingEntry pendingLending = null;
	private volatile String lastLendingTarget = null;

	@Override
	protected void startUp() throws Exception
	{
		dataService.initialize();
		groupService.initialize();
		localDataSyncService.initialize();

		BufferedImage icon;
		try
		{
			icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
		}
		catch (Exception e)
		{
			log.warn("Failed to load panel icon, using default: {}", e.getMessage());
			icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = icon.createGraphics();
			g.setColor(Color.ORANGE);
			g.fillOval(2, 2, 12, 12);
			g.setColor(Color.WHITE);
			g.drawString("L", 6, 12);
			g.dispose();
		}

		newPanel = new LendingPanel(this, eventBus);
		navButton = NavigationButton.builder()
			.tooltip("Lending Tracker").icon(icon).priority(5).panel(newPanel).build();
		if (clientToolbar != null) { clientToolbar.addNavigation(navButton); }
		else { log.error("ClientToolbar is null - UI will not appear"); }

		groupService.setOnSyncCallback(() -> { if (newPanel != null) { newPanel.refresh(); } });

		if (client.getGameState() == GameState.LOGGED_IN
			&& client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
		{
			triggerLoginFlow(client.getLocalPlayer().getName());
		}

		try
		{
			executor.scheduleAtFixedRate(this::checkOverdueLoans, 0, 1, TimeUnit.HOURS);
			executor.scheduleAtFixedRate(this::cleanupOldRecords, 1, 24, TimeUnit.HOURS);
			executor.scheduleAtFixedRate(this::syncGroupData, 0, 5, TimeUnit.MINUTES);
			executor.scheduleAtFixedRate(this::updateMarketplacePrices, 1, 12, TimeUnit.HOURS);
		}
		catch (Exception e) { log.warn("Failed to schedule periodic tasks: {}", e.getMessage()); }
	}

	@Override
	protected void shutDown() throws Exception
	{
		groupService.stopSync();
		if (navButton != null) { clientToolbar.removeNavigation(navButton); }
		if (localDataSyncService != null)
		{
			try { localDataSyncService.shutdown(); }
			catch (Exception e) { log.warn("Error shutting down local data sync: {}", e.getMessage()); }
		}
		newPanel = null;
		navButton = null;
		pendingLending = null;
	}

	private void triggerLoginFlow(String playerName)
	{
		groupService.onAccountLogin(playerName);
		localDataSyncService.onAccountLogin();
		configManager.setConfiguration("lendingtracker", "currentAccount", playerName);
		LendingGroup activeGroup = groupService.getActiveGroup();
		if (activeGroup != null) { groupService.startSync(activeGroup.getId(), playerName); }
		if (newPanel != null) { newPanel.refresh(); }
	}

	// --- Event Handlers ---

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == LENDING_WIDGET_GROUP)
		{
			clientThread.invokeLater(this::processLendingInterface);
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		String target = event.getTarget();
		if (target == null || target.isEmpty()) { return; }

		if (event.getOption().equals("Examine")
			&& (event.getType() == MenuAction.EXAMINE_ITEM.getId()
				|| event.getType() == MenuAction.EXAMINE_ITEM_GROUND.getId()))
		{
			addMenuEntry("Add to Lending List", event);
		}

		if (event.getOption().equals("Drop"))
		{
			addMenuEntry("Lend to Group", event);
		}

		if (isPlayerMenuAction(event.getType()) && canCurrentUserInvite())
		{
			addMenuEntry("Invite to Lending Group", event);
		}
	}

	private void addMenuEntry(String option, MenuEntryAdded event)
	{
		client.createMenuEntry(-1)
			.setOption(option).setTarget(event.getTarget()).setType(MenuAction.RUNELITE)
			.setParam0(event.getActionParam0()).setParam1(event.getActionParam1())
			.setIdentifier(event.getIdentifier());
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = event.getMenuOption();
		if (option.equals("Add to Lending List")) { handleAddToAvailableList(event); }
		else if (option.equals("Lend to Group")) { handleLendToGroup(event); }
		else if (option.equals("Invite to Lending Group")) { handlePlayerInvite(event); }
		else if (option.contains("Lend")) { lastLendingTarget = event.getMenuTarget(); }
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		String message = event.getMessage();
		if (event.getType() == ChatMessageType.PRIVATECHAT || event.getType() == ChatMessageType.PRIVATECHATOUT)
		{
			handlePrivateMessage(event.getName(), message);
		}
		if (event.getType() != ChatMessageType.GAMEMESSAGE) { return; }
		if (message.contains("You lend") && message.contains("to")) { handleLendingConfirmation(); }
		if (message.contains("returned your") || message.contains("gives you back")) { handleReturnConfirmation(message); }
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(() ->
			{
				if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null) { return false; }
				String playerName = client.getLocalPlayer().getName();
				groupService.setOnSyncCallback(() -> { if (newPanel != null) { newPanel.refresh(); } });
				triggerLoginFlow(playerName);
				return true;
			});
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			groupService.stopSync();
			if (newPanel != null) { newPanel.refresh(); }
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		clientThread.invokeLater(() ->
		{
			if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null) { return false; }
			String playerName = client.getLocalPlayer().getName();
			triggerLoginFlow(playerName);
			return true;
		});
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarbitId() == Varbits.IN_WILDERNESS
			&& client.getVarbitValue(Varbits.IN_WILDERNESS) == 1)
		{
			checkBorrowedItemsInWilderness();
		}
	}

	// --- Lending Interface ---

	private void processLendingInterface()
	{
		if (client == null) { return; }
		Widget acceptButton = client.getWidget(LENDING_WIDGET_GROUP, ACCEPT_BUTTON);
		if (acceptButton == null) { return; }

		String itemName = getWidgetText(2);
		int itemId = getWidgetItemId(3);
		int quantity = getWidgetItemQuantity(4);
		String playerName = getWidgetText(5);
		if (playerName == null) { playerName = lastLendingTarget; }

		if (itemName != null && !itemName.isEmpty() && playerName != null && !playerName.isEmpty())
		{
			pendingLending = new LendingEntry();
			pendingLending.setId(UUID.randomUUID().toString());
			pendingLending.setBorrower(playerName);
			pendingLending.setItem(itemName);
			pendingLending.setItemId(itemId);
			pendingLending.setQuantity(quantity);
			pendingLending.setValue(calculateItemValue(itemId, quantity));
			pendingLending.setLendTime(Instant.now().toEpochMilli());
			pendingLending.setDueTime(Instant.now().plus(config.defaultLoanDuration(), ChronoUnit.DAYS).toEpochMilli());
		}
	}

	private String getWidgetText(int child)
	{
		try { Widget w = client.getWidget(LENDING_WIDGET_GROUP, child); return w != null ? w.getText() : null; }
		catch (Exception e) { return null; }
	}

	private int getWidgetItemId(int child)
	{
		try { Widget w = client.getWidget(LENDING_WIDGET_GROUP, child); return w != null ? w.getItemId() : -1; }
		catch (Exception e) { return -1; }
	}

	private int getWidgetItemQuantity(int child)
	{
		try { Widget w = client.getWidget(LENDING_WIDGET_GROUP, child); return w != null ? w.getItemQuantity() : 1; }
		catch (Exception e) { return 1; }
	}

	// --- Chat Handlers ---

	private void handleLendingConfirmation()
	{
		if (pendingLending == null) { return; }
		dataService.addEntry(pendingLending);
		LendingGroup activeGroup = groupService.getActiveGroup();
		if (activeGroup != null) { groupService.syncLending(activeGroup.getId(), pendingLending); }
		pendingLending = null;
		if (newPanel != null) { newPanel.refresh(); }
	}

	private void handleReturnConfirmation(String message)
	{
		String playerName = null;
		String itemName = null;
		if (message.contains(" returned your "))
		{
			playerName = message.substring(0, message.indexOf(" returned your ")).trim();
			itemName = message.substring(message.indexOf(" returned your ") + 15).replaceAll("[.]", "").trim();
		}
		else if (message.contains(" gives you back "))
		{
			playerName = message.substring(0, message.indexOf(" gives you back ")).trim();
			itemName = message.substring(message.indexOf(" gives you back ") + 16).replaceAll("[.]", "").trim();
		}
		if (playerName == null || itemName == null) { return; }

		final String pName = playerName;
		final String iName = itemName;
		List<LendingEntry> entries = dataService.getEntriesForPlayer(pName).stream()
			.filter(e -> e.getItemName().equals(iName) && !e.isReturned())
			.collect(Collectors.toList());
		if (!entries.isEmpty())
		{
			dataService.completeEntry(entries.get(0).getId(), true);
			if (newPanel != null) { newPanel.refresh(); }
		}
	}

	// --- Scheduled Tasks ---

	private void checkOverdueLoans()
	{
		List<LendingEntry> overdueEntries = dataService.getOverdueEntries();
		if (overdueEntries.isEmpty() || !config.enableNotifications()) { return; }
		for (LendingEntry entry : overdueEntries)
		{
			long daysOverdue = ChronoUnit.DAYS.between(Instant.ofEpochMilli(entry.getDueDate()), Instant.now());
			if (daysOverdue > 0 && daysOverdue % config.overdueReminderFrequency() == 0)
			{
				notifier.notify("Overdue loan: " + entry.getItemName() + " (" + daysOverdue + " days overdue)");
				if (config.enableSoundAlerts()) { client.playSoundEffect(SoundEffectID.UI_BOOP); }
			}
		}
	}

	private void syncGroupData()
	{
		LendingGroup g = groupService.getActiveGroup();
		if (g != null) { groupService.syncAllEntries(g.getId(), dataService.getActiveEntries()); }
	}

	private void cleanupOldRecords()
	{
		int days = config.dataRetentionDays();
		if (days <= 0) { return; }
		dataService.deleteOldReturnedEntries(System.currentTimeMillis() - (days * 86400000L));
	}

	private void updateMarketplacePrices()
	{
		try
		{
			String groupId = groupService.getCurrentGroupIdUnchecked();
			if (groupId == null || groupId.isEmpty()) { return; }
			List<LendingEntry> available = dataService.getAvailable(groupId);
			if (available == null || available.isEmpty()) { return; }

			List<LendingEntry> toUpdate = available.stream()
				.filter(e -> e.getBorrower() == null || e.getBorrower().isEmpty())
				.collect(Collectors.toList());
			if (toUpdate.isEmpty()) { return; }

			clientThread.invokeLater(() ->
			{
				int updated = 0;
				for (LendingEntry entry : toUpdate)
				{
					if (entry.getItemId() > 0)
					{
						int p = itemManager.getItemPrice(entry.getItemId());
						if (p > 0 && p != entry.getValue()) { entry.setValue(p); updated++; }
					}
				}
				if (updated > 0)
				{
					for (LendingEntry e : toUpdate)
					{
						dataService.updateAvailable(groupId, e.getLender(), e.getItem(), e.getItemId(), e);
					}
					if (newPanel != null) { SwingUtilities.invokeLater(() -> newPanel.refresh()); }
				}
			});
		}
		catch (Exception e) { log.warn("Failed to update marketplace prices: {}", e.getMessage()); }
	}

	// --- Menu Handlers ---

	private void handleAddToAvailableList(MenuOptionClicked event)
	{
		String itemName = event.getMenuTarget().replaceAll("<[^>]*>", "").trim();
		int itemId = event.getId();
		if (itemName.isEmpty()) { return; }

		SwingUtilities.invokeLater(() ->
		{
			String input = JOptionPane.showInputDialog(null,
				"How many " + itemName + " are you lending?", "Add to Available List", JOptionPane.QUESTION_MESSAGE);
			if (input == null || input.isEmpty()) { return; }
			try
			{
				int qty = Integer.parseInt(input);
				LendingEntry entry = new LendingEntry();
				entry.setId(UUID.randomUUID().toString());
				entry.setItem(itemName);
				entry.setItemId(itemId);
				entry.setQuantity(qty);
				entry.setValue(calculateItemValue(itemId, qty));
				entry.setLender(client != null && client.getLocalPlayer() != null
					? client.getLocalPlayer().getName() : "Unknown");
				entry.setLendTime(Instant.now().toEpochMilli());
				dataService.addToAvailableList(entry, groupService.getCurrentGroupId());
				if (newPanel != null) { newPanel.refresh(); }
			}
			catch (NumberFormatException e)
			{
				JOptionPane.showMessageDialog(null, "Please enter a valid number.", "Invalid Input", JOptionPane.ERROR_MESSAGE);
			}
		});
	}

	private void handleLendToGroup(MenuOptionClicked event)
	{
		String target = event.getMenuTarget();
		if (target == null || target.isEmpty()) { return; }

		String itemName = Text.removeTags(target);
		int itemId = -1;
		try
		{
			ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
			if (inv != null)
			{
				Item[] items = inv.getItems();
				int slot = event.getParam0();
				if (slot >= 0 && slot < items.length) { itemId = items[slot].getId(); }
			}
		}
		catch (Exception ex) { log.error("Could not get item ID from inventory", ex); }

		if (itemId == -1)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Error: Could not determine item ID", "");
			return;
		}
		String gid = groupService.getCurrentGroupId();
		if (gid == null || gid.isEmpty())
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Error: You must be in a group to lend items", "");
			return;
		}

		int itemPrice = 0;
		try
		{
			itemPrice = itemManager.getItemPrice(itemId);
			if (!itemManager.getItemComposition(itemId).isTradeable())
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Cannot lend " + itemName + " - This item is untradeable", "");
				return;
			}
		}
		catch (Exception ex) { log.warn("Could not check tradeability: {}", itemId, ex); }

		final String fn = itemName; final int fi = itemId; final int fp = itemPrice;
		SwingUtilities.invokeLater(() ->
		{
			try { showLendItemDialog(fn, fi, gid, fp); }
			catch (Exception ex)
			{
				log.error("Error in showLendItemDialog", ex);
				clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"ERROR: Could not open lend dialog - " + ex.getMessage(), ""));
			}
		});
	}

	private void handlePlayerInvite(MenuOptionClicked event)
	{
		String target = event.getMenuTarget();
		if (target == null || target.isEmpty()) { return; }
		String playerName = extractPlayerNameFromTarget(target);
		if (playerName == null || playerName.isEmpty()) { return; }
		if (!canCurrentUserInvite()) { showNotification("Permission Denied", "Only owners/admins can invite"); return; }
		LendingGroup group = groupService.getActiveGroup();
		if (group == null) { showNotification("No Group", "No active lending group selected"); return; }
		String msg = "Join my lending group '" + group.getName() + "' with code: " + group.getInviteCode();
		SwingUtilities.invokeLater(() -> showInviteDialog(playerName, msg));
	}

	// --- Dialogs ---

	private void showLendItemDialog(String itemName, int itemId, String groupId, int itemPrice)
	{
		JDialog dlg = new JDialog((Frame) null, "Lend " + itemName + " to Group", false);
		dlg.setSize(500, 500);
		dlg.setLocationRelativeTo(null);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		JPanel main = new JPanel(new BorderLayout(10, 10));
		main.setBackground(ColorScheme.DARK_GRAY_COLOR);
		main.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel itemLabel = new JLabel("Item: " + itemName);
		itemLabel.setForeground(Color.WHITE);
		itemLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(itemLabel);
		content.add(Box.createVerticalStrut(15));

		SpinnerNumberModel colModel = new SpinnerNumberModel(itemPrice, 0, Integer.MAX_VALUE, 100000);
		JSpinner colSpinner = new JSpinner(colModel);
		colSpinner.setPreferredSize(new Dimension(150, 25));
		content.add(labeledRow("Collateral (GP):", colSpinner));
		content.add(Box.createVerticalStrut(5));

		SpinnerNumberModel pctModel = new SpinnerNumberModel(100, 0, 1000, 10);
		JSpinner pctSpinner = new JSpinner(pctModel);
		pctSpinner.setPreferredSize(new Dimension(80, 25));
		pctSpinner.addChangeListener(ev -> colSpinner.setValue((int) (itemPrice * ((int) pctSpinner.getValue() / 100.0))));
		content.add(labeledRow("Collateral % (100% = price):", pctSpinner));
		content.add(Box.createVerticalStrut(10));

		SpinnerNumberModel durModel = new SpinnerNumberModel(24, 1, 168, 1);
		JSpinner durSpinner = new JSpinner(durModel);
		durSpinner.setPreferredSize(new Dimension(100, 25));
		content.add(labeledRow("Duration (hours):", durSpinner));
		content.add(Box.createVerticalStrut(5));

		JCheckBox noDur = new JCheckBox("No Duration (Keep Until Removed)");
		noDur.setBackground(ColorScheme.DARK_GRAY_COLOR);
		noDur.setForeground(Color.WHITE);
		noDur.addActionListener(ev -> durSpinner.setEnabled(!noDur.isSelected()));
		noDur.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(noDur);
		content.add(Box.createVerticalStrut(10));

		JLabel notesLbl = new JLabel("Notes (optional):");
		notesLbl.setForeground(Color.WHITE);
		notesLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(notesLbl);
		content.add(Box.createVerticalStrut(5));
		JTextArea notesArea = new JTextArea(3, 30);
		notesArea.setLineWrap(true);
		notesArea.setWrapStyleWord(true);
		JScrollPane scroll = new JScrollPane(notesArea);
		scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(scroll);
		main.add(content, BorderLayout.CENTER);

		JPanel btns = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
		btns.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JButton addBtn = new JButton("Add to Marketplace");
		addBtn.addActionListener(ev -> addToMarketplace(dlg, itemName, itemId, groupId, itemPrice,
			(int) colSpinner.getValue(), noDur.isSelected() ? 0 : (int) durSpinner.getValue(),
			noDur.isSelected(), notesArea.getText().trim()));
		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.addActionListener(ev -> dlg.dispose());
		btns.add(addBtn);
		btns.add(cancelBtn);
		main.add(btns, BorderLayout.SOUTH);

		dlg.add(main);
		dlg.getRootPane().setDefaultButton(addBtn);
		dlg.setVisible(true);
		dlg.toFront();
		dlg.requestFocus();
	}

	private void addToMarketplace(JDialog dlg, String itemName, int itemId, String groupId,
		int itemPrice, int collateral, int duration, boolean noDuration, String notes)
	{
		try
		{
			int gePrice = itemPrice > 0 ? itemPrice : itemManager.getItemPrice(itemId);
			StringBuilder s = new StringBuilder();
			s.append("Item: ").append(itemName).append("\n");
			s.append("GE Value: ").append(QuantityFormatter.quantityToStackSize(gePrice)).append(" GP\n");
			if (collateral > 0) { s.append("Collateral: ").append(collateral).append(" GP\n"); }
			s.append("Duration: ").append(noDuration ? "No limit" : duration + " hours").append("\n");
			if (!notes.isEmpty()) { s.append("Notes: ").append(notes).append("\n"); }
			s.append("\nAdd this item to the marketplace?");
			if (JOptionPane.showConfirmDialog(dlg, s.toString(), "Confirm Listing",
				JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return; }

			String player = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Unknown";
			LendingEntry offer = new LendingEntry();
			offer.setLender(player);
			offer.setBorrower("");
			offer.setItem(itemName);
			offer.setItemId(itemId);
			offer.setQuantity(1);
			offer.setCollateralValue(collateral);
			offer.setCollateralType(collateral > 0 ? "GP" : "none");
			offer.setNotes(notes);
			offer.setValue(gePrice);
			offer.setGroupId(groupId);
			offer.setLendTime(System.currentTimeMillis());
			offer.setDueTime(noDuration ? Long.MAX_VALUE : System.currentTimeMillis() + (duration * 3600000L));
			offer.setReturnedAt(0L);
			offer.setId(UUID.randomUUID().toString());

			dataService.addAvailable(groupId, player, offer);
			dlg.dispose();
			if (newPanel != null) { SwingUtilities.invokeLater(() -> newPanel.refresh()); }
			clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"Added " + itemName + " to group marketplace", ""));
		}
		catch (Exception ex) { log.error("Error adding to marketplace", ex); }
	}

	private JPanel labeledRow(String label, JComponent field)
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel lbl = new JLabel(label);
		lbl.setForeground(Color.WHITE);
		row.add(lbl);
		row.add(field);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	private void showInviteDialog(String playerName, String inviteMessage)
	{
		JDialog dlg = new JDialog();
		dlg.setTitle("Send Invite to " + playerName);
		dlg.setModal(true);
		dlg.setSize(400, 200);
		dlg.setLocationRelativeTo(null);

		JPanel p = new JPanel(new GridBagLayout());
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		GridBagConstraints c = new GridBagConstraints();

		c.gridx = 0; c.gridy = 0; c.gridwidth = 2; c.insets = new Insets(10, 10, 10, 10);
		JLabel lbl = new JLabel("<html>Send this PM to <b>" + playerName + "</b>:</html>");
		lbl.setForeground(Color.WHITE);
		p.add(lbl, c);

		c.gridy = 1; c.insets = new Insets(5, 10, 10, 10);
		JTextField msgField = new JTextField(inviteMessage);
		msgField.setPreferredSize(new Dimension(350, 25));
		msgField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		msgField.setForeground(Color.WHITE);
		msgField.setEditable(false);
		msgField.selectAll();
		p.add(msgField, c);

		c.gridy = 2; c.gridwidth = 1; c.insets = new Insets(10, 10, 10, 5);
		JButton copyBtn = new JButton("Copy Message");
		copyBtn.addActionListener(ev ->
		{
			java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
				new java.awt.datatransfer.StringSelection(inviteMessage), null);
			JOptionPane.showMessageDialog(dlg, "Copied!", "Copied", JOptionPane.INFORMATION_MESSAGE);
		});
		p.add(copyBtn, c);

		c.gridx = 1; c.insets = new Insets(10, 5, 10, 10);
		JButton closeBtn = new JButton("Close");
		closeBtn.addActionListener(ev -> dlg.dispose());
		p.add(closeBtn, c);
		dlg.add(p);
		dlg.setVisible(true);
	}

	// --- PM / Invite Processing ---

	private void handlePrivateMessage(String sender, String message)
	{
		if (sender == null || message == null) { return; }
		String lower = message.toLowerCase();
		if (lower.contains("join") && lower.contains("code:")) { processInviteRequest(sender, message); }
	}

	private void processInviteRequest(String sender, String message)
	{
		String[] parts = message.split("code:");
		if (parts.length < 2) { return; }
		String[] codeWords = parts[1].trim().split("\\s+");
		if (codeWords.length == 0 || codeWords[0].length() < 8) { return; }
		String code = codeWords[0].substring(0, 8);

		LendingGroup target = null;
		for (LendingGroup g : groupService.getAllGroups())
		{
			if (code.equals(g.getInviteCode())) { target = g; break; }
		}
		if (target == null) { return; }

		String me = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
		if (me == null) { return; }
		if (!groupService.isOwner(target.getId(), me) && !groupService.isAdmin(target.getId(), me)) { return; }
		if (target.hasMember(sender))
		{
			showNotification("Already Member", sender + " is already in " + target.getName());
			return;
		}

		final LendingGroup fg = target;
		SwingUtilities.invokeLater(() ->
		{
			if (JOptionPane.showConfirmDialog(null,
				sender + " wants to join '" + fg.getName() + "'. Accept?",
				"Group Invite", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
			{
				groupService.addMember(fg.getId(), sender, "member");
				showNotification("Added", sender + " joined " + fg.getName());
				if (newPanel != null) { newPanel.refresh(); }
			}
		});
	}

	// --- Wilderness ---

	private void checkBorrowedItemsInWilderness()
	{
		String me = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
		if (me == null) { return; }

		List<LendingEntry> borrowed = new ArrayList<>();
		for (LendingGroup g : groupService.getAllGroups())
		{
			borrowed.addAll(dataService.getBorrowed(g.getId()).stream()
				.filter(e -> me.equals(e.getBorrower()) && !e.isReturned())
				.collect(Collectors.toList()));
		}
		if (!borrowed.isEmpty())
		{
			long total = borrowed.stream().mapToLong(LendingEntry::getValue).sum();
			log.warn("Wilderness with {} borrowed items, value: {} GP",
				borrowed.size(), QuantityFormatter.quantityToStackSize(total));
		}
	}

	// --- Helpers ---

	private boolean isPlayerMenuAction(int type)
	{
		return type == MenuAction.PLAYER_FIRST_OPTION.getId()
			|| type == MenuAction.PLAYER_SECOND_OPTION.getId()
			|| type == MenuAction.PLAYER_THIRD_OPTION.getId()
			|| type == MenuAction.PLAYER_FOURTH_OPTION.getId()
			|| type == MenuAction.PLAYER_FIFTH_OPTION.getId()
			|| type == MenuAction.PLAYER_SIXTH_OPTION.getId()
			|| type == MenuAction.PLAYER_SEVENTH_OPTION.getId()
			|| type == MenuAction.PLAYER_EIGHTH_OPTION.getId();
	}

	private boolean canCurrentUserInvite()
	{
		if (client == null || client.getLocalPlayer() == null) { return false; }
		String name = client.getLocalPlayer().getName();
		if (name == null || name.isEmpty() || groupService == null) { return false; }
		LendingGroup g = groupService.getActiveGroup();
		if (g == null) { return false; }
		return groupService.isOwner(g.getId(), name) || groupService.isAdmin(g.getId(), name);
	}

	private String extractPlayerNameFromTarget(String target)
	{
		if (target == null) { return null; }
		String[] parts = target.replaceAll("<[^>]*>", "").trim().split("\\s+");
		return parts.length > 0 ? parts[0] : null;
	}

	private long calculateItemValue(int itemId, int quantity)
	{
		return itemId <= 0 ? 0 : (long) itemManager.getItemPrice(itemId) * quantity;
	}

	private void showNotification(String title, String message)
	{
		SwingUtilities.invokeLater(() ->
			JOptionPane.showMessageDialog(null, message, title, JOptionPane.INFORMATION_MESSAGE));
	}

	// --- Public API for UI Panels ---

	/** Stub: borrow request (called by DashboardPanel) */
	public void sendBorrowRequest(String borrower, String lender, String itemName, int itemId, int quantity, int durationDays)
	{
		// TODO: Implement
	}

	/** Stub: lend offer (called by DashboardPanel) */
	public void sendLendOffer(String lender, String borrower, String itemName, int quantity, int durationDays, String message, String durationDisplay)
	{
		// TODO: Implement
	}

	public void refreshPanel()
	{
		if (newPanel != null) { SwingUtilities.invokeLater(() -> newPanel.refresh()); }
	}

	public String getCurrentPlayerName()
	{
		if (client != null && client.getLocalPlayer() != null) { return client.getLocalPlayer().getName(); }
		String stored = configManager.getConfiguration("lendingtracker", "currentAccount");
		return (stored != null && !stored.isEmpty()) ? stored : null;
	}

	public Client getClient() { return client; }
	public ClientThread getClientThread() { return clientThread; }
	public ConfigManager getConfigManager() { return configManager; }
	public ItemManager getItemManager() { return itemManager; }
	public DataService getDataService() { return dataService; }
	public GroupService getGroupService() { return groupService; }
	public ProofScreenshot getProofScreenshot() { return proofScreenshot; }

	@Provides
	LendingTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LendingTrackerConfig.class);
	}
}
