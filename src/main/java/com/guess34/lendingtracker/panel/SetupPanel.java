package com.guess34.lendingtracker.panel;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import com.guess34.lendingtracker.model.LendingGroup;
import com.guess34.lendingtracker.services.group.GroupConfigStore;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Setup panel for account management and joining/creating groups
 */
@Slf4j
public class SetupPanel extends JPanel
{
	private final GroupConfigStore groupConfigStore;
	private final Client client;
	private final Runnable refreshCallback;

	public SetupPanel(GroupConfigStore groupConfigStore, Client client, Runnable refreshCallback)
	{
		this.groupConfigStore = groupConfigStore;
		this.client = client;
		this.refreshCallback = refreshCallback;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel mainContent = new JPanel();
		mainContent.setLayout(new BoxLayout(mainContent, BoxLayout.Y_AXIS));
		mainContent.setBackground(ColorScheme.DARK_GRAY_COLOR);
		mainContent.setBorder(new EmptyBorder(15, 15, 15, 15));

		// Account Info Section
		mainContent.add(buildAccountInfoSection());
		mainContent.add(Box.createVerticalStrut(20));

		// Join Group Section
		mainContent.add(buildJoinGroupSection());
		mainContent.add(Box.createVerticalStrut(20));

		// Create Group Section
		mainContent.add(buildCreateGroupSection());

		JScrollPane scrollPane = new JScrollPane(mainContent);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		styleScrollBar(scrollPane);
		add(scrollPane, BorderLayout.CENTER);
	}

	private JPanel buildAccountInfoSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		section.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE),
			"Account Information",
			javax.swing.border.TitledBorder.LEFT,
			javax.swing.border.TitledBorder.TOP,
			new Font("Arial", Font.BOLD, 12),
			ColorScheme.BRAND_ORANGE
		));

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		content.setBorder(new EmptyBorder(10, 10, 10, 10));

		// Current player name
		String playerName = getCurrentPlayerName();
		JLabel nameLabel = new JLabel("Current Player: " + playerName);
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setFont(new Font("Arial", Font.BOLD, 14));
		nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(nameLabel);

		content.add(Box.createVerticalStrut(10));

		JLabel infoLabel = new JLabel("<html>This is the name that will be used when joining groups.<br>" +
			"Make sure you're logged into the correct account.</html>");
		infoLabel.setForeground(Color.LIGHT_GRAY);
		infoLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(infoLabel);

		section.add(content);
		return section;
	}

	private JPanel buildJoinGroupSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		section.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE),
			"Join Group",
			javax.swing.border.TitledBorder.LEFT,
			javax.swing.border.TitledBorder.TOP,
			new Font("Arial", Font.BOLD, 12),
			ColorScheme.BRAND_ORANGE
		));

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		content.setBorder(new EmptyBorder(10, 10, 10, 10));

		// Code input field
		JLabel codeLabel = new JLabel("Enter Group/Clan Code:");
		codeLabel.setForeground(Color.WHITE);
		codeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(codeLabel);

		content.add(Box.createVerticalStrut(5));

		JTextField codeField = new JTextField();
		codeField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		codeField.setAlignmentX(Component.LEFT_ALIGNMENT);
		codeField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		codeField.setForeground(Color.WHITE);
		codeField.setCaretColor(Color.WHITE);
		content.add(codeField);

		content.add(Box.createVerticalStrut(10));

		// Group name preview (shows when valid code entered)
		JLabel groupNameLabel = new JLabel("");
		groupNameLabel.setForeground(ColorScheme.BRAND_ORANGE);
		groupNameLabel.setFont(new Font("Arial", Font.BOLD, 12));
		groupNameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(groupNameLabel);

		content.add(Box.createVerticalStrut(10));

		// Buttons
		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		buttonPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttonPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));
		buttonPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JButton checkCodeBtn = new JButton("Check Code");
		checkCodeBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		checkCodeBtn.setForeground(Color.WHITE);
		checkCodeBtn.addActionListener(e -> {
			String code = codeField.getText().trim();
			if (code.isEmpty())
			{
				groupNameLabel.setText("Please enter a code");
				groupNameLabel.setForeground(Color.RED);
				return;
			}

			LendingGroup group = groupConfigStore.findGroupByCode(code);
			if (group != null)
			{
				groupNameLabel.setText("Group: " + group.getName());
				groupNameLabel.setForeground(ColorScheme.BRAND_ORANGE);
			}
			else
			{
				groupNameLabel.setText("Invalid code");
				groupNameLabel.setForeground(Color.RED);
			}
		});
		buttonPanel.add(checkCodeBtn);

		JButton joinBtn = new JButton("Join Group");
		joinBtn.setBackground(Color.GREEN.darker());
		joinBtn.setForeground(Color.WHITE);
		joinBtn.addActionListener(e -> {
			String code = codeField.getText().trim();
			String playerName = getCurrentPlayerName();

			if (code.isEmpty())
			{
				JOptionPane.showMessageDialog(this,
					"Please enter a group/clan code",
					"Error",
					JOptionPane.ERROR_MESSAGE);
				return;
			}

			boolean success = groupConfigStore.joinGroupWithCode(code, playerName);
			if (success)
			{
				LendingGroup group = groupConfigStore.findGroupByCode(code);
				JOptionPane.showMessageDialog(this,
					"Join request sent to group: " + group.getName() + "\nWaiting for approval from group admins.",
					"Success",
					JOptionPane.INFORMATION_MESSAGE);
				codeField.setText("");
				groupNameLabel.setText("");
				if (refreshCallback != null) refreshCallback.run();
			}
			else
			{
				JOptionPane.showMessageDialog(this,
					"Failed to join group. Code may be invalid or you've already used this single-use code.",
					"Error",
					JOptionPane.ERROR_MESSAGE);
			}
		});
		buttonPanel.add(joinBtn);

		content.add(buttonPanel);

		section.add(content);
		return section;
	}

	private JPanel buildCreateGroupSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		section.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE),
			"Create New Group",
			javax.swing.border.TitledBorder.LEFT,
			javax.swing.border.TitledBorder.TOP,
			new Font("Arial", Font.BOLD, 12),
			ColorScheme.BRAND_ORANGE
		));

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		content.setBorder(new EmptyBorder(10, 10, 10, 10));

		// Group name field
		JLabel nameLabel = new JLabel("Group Name:");
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(nameLabel);

		content.add(Box.createVerticalStrut(5));

		JTextField nameField = new JTextField();
		nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		nameField.setAlignmentX(Component.LEFT_ALIGNMENT);
		nameField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		nameField.setForeground(Color.WHITE);
		nameField.setCaretColor(Color.WHITE);
		content.add(nameField);

		content.add(Box.createVerticalStrut(10));

		// Description field
		JLabel descLabel = new JLabel("Description (optional):");
		descLabel.setForeground(Color.WHITE);
		descLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(descLabel);

		content.add(Box.createVerticalStrut(5));

		JTextField descField = new JTextField();
		descField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		descField.setAlignmentX(Component.LEFT_ALIGNMENT);
		descField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		descField.setForeground(Color.WHITE);
		descField.setCaretColor(Color.WHITE);
		content.add(descField);

		content.add(Box.createVerticalStrut(15));

		// Create button
		JButton createBtn = new JButton("Create Group");
		createBtn.setBackground(Color.GREEN.darker());
		createBtn.setForeground(Color.WHITE);
		createBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));
		createBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
		createBtn.addActionListener(e -> {
			String groupName = nameField.getText().trim();
			String description = descField.getText().trim();
			String playerName = getCurrentPlayerName();

			if (groupName.isEmpty())
			{
				JOptionPane.showMessageDialog(this,
					"Please enter a group name",
					"Error",
					JOptionPane.ERROR_MESSAGE);
				return;
			}

			if (groupConfigStore.isGroupNameTaken(groupName))
			{
				JOptionPane.showMessageDialog(this,
					"A group with the name \"" + groupName + "\" already exists.\nPlease choose a different name.",
					"Name Taken",
					JOptionPane.ERROR_MESSAGE);
				return;
			}

			String groupId = groupConfigStore.createGroup(groupName, description, playerName);
			if (groupId != null)
			{
				JOptionPane.showMessageDialog(this,
					"Group \"" + groupName + "\" created successfully!\nYou are the owner.",
					"Success",
					JOptionPane.INFORMATION_MESSAGE);
				nameField.setText("");
				descField.setText("");
				if (refreshCallback != null) refreshCallback.run();
			}
			else
			{
				JOptionPane.showMessageDialog(this,
					"Failed to create group",
					"Error",
					JOptionPane.ERROR_MESSAGE);
			}
		});
		content.add(createBtn);

		section.add(content);
		return section;
	}

	private String getCurrentPlayerName()
	{
		if (client == null || client.getLocalPlayer() == null)
		{
			return "Unknown";
		}
		String name = client.getLocalPlayer().getName();
		return (name != null && !name.isEmpty()) ? name : "Unknown";
	}

	private void styleScrollBar(JScrollPane scrollPane)
	{
		scrollPane.getVerticalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
			@Override
			protected void configureScrollBarColors() {
				this.thumbColor = ColorScheme.BRAND_ORANGE;
				this.trackColor = ColorScheme.DARKER_GRAY_COLOR;
			}
			@Override
			protected JButton createDecreaseButton(int orientation) {
				JButton button = super.createDecreaseButton(orientation);
				button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				button.setForeground(ColorScheme.BRAND_ORANGE);
				return button;
			}
			@Override
			protected JButton createIncreaseButton(int orientation) {
				JButton button = super.createIncreaseButton(orientation);
				button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				button.setForeground(ColorScheme.BRAND_ORANGE);
				return button;
			}
		});
		scrollPane.getHorizontalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
			@Override
			protected void configureScrollBarColors() {
				this.thumbColor = ColorScheme.BRAND_ORANGE;
				this.trackColor = ColorScheme.DARKER_GRAY_COLOR;
			}
			@Override
			protected JButton createDecreaseButton(int orientation) {
				JButton button = super.createDecreaseButton(orientation);
				button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				button.setForeground(ColorScheme.BRAND_ORANGE);
				return button;
			}
			@Override
			protected JButton createIncreaseButton(int orientation) {
				JButton button = super.createIncreaseButton(orientation);
				button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				button.setForeground(ColorScheme.BRAND_ORANGE);
				return button;
			}
		});
	}
}
