package com.guess34.lendingtracker.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.http.api.item.ItemPrice;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * GE-style autocomplete search field for items
 * Provides real-time item suggestions as user types
 */
@Slf4j
public class ItemSearchField extends JPanel
{
	private static final int MAX_SUGGESTIONS = 10;
	private static final int MIN_SEARCH_LENGTH = 2;
	private static final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();

	private final JTextField searchField;
	private final JPopupMenu suggestionPopup;
	private final ItemManager itemManager;
	private Consumer<String> onItemSelected;
	private SwingWorker<List<String>, Void> currentSearchWorker;

	public ItemSearchField(ItemManager itemManager)
	{
		this.itemManager = itemManager;
		this.searchField = new JTextField();
		this.suggestionPopup = new JPopupMenu();

		setLayout(new BorderLayout());
		setupSearchField();
		setupSuggestionPopup();
		setupListeners();

		add(searchField, BorderLayout.CENTER);
	}

	private void setupSearchField()
	{
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.setForeground(Color.WHITE);
		searchField.setCaretColor(Color.WHITE);
		searchField.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1),
			BorderFactory.createEmptyBorder(5, 8, 5, 8)
		));
	}

	private void setupSuggestionPopup()
	{
		suggestionPopup.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		suggestionPopup.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1));
		suggestionPopup.setFocusable(false);
	}

	private void setupListeners()
	{
		// Document listener for real-time search
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				scheduleSearch();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				scheduleSearch();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				scheduleSearch();
			}
		});

		// Hide popup when field loses focus
		searchField.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent e)
			{
				// Delay hiding to allow clicking suggestions
				SwingUtilities.invokeLater(() ->
				{
					if (!suggestionPopup.isVisible())
					{
						return;
					}
					// Check if focus went to the popup
					Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
					if (focusOwner == null || !SwingUtilities.isDescendingFrom(focusOwner, suggestionPopup))
					{
						suggestionPopup.setVisible(false);
					}
				});
			}
		});
	}

	private void scheduleSearch()
	{
		// Cancel previous search if still running
		if (currentSearchWorker != null && !currentSearchWorker.isDone())
		{
			currentSearchWorker.cancel(true);
		}

		String query = searchField.getText().trim();

		// Hide popup if query too short
		if (query.length() < MIN_SEARCH_LENGTH)
		{
			suggestionPopup.setVisible(false);
			return;
		}

		// Start new search in background
		currentSearchWorker = new SwingWorker<List<String>, Void>()
		{
			@Override
			protected List<String> doInBackground()
			{
				return searchItems(query);
			}

			@Override
			protected void done()
			{
				if (!isCancelled())
				{
					try
					{
						List<String> results = get();
						showSuggestions(results);
					}
					catch (Exception e)
					{
						log.error("Error during item search", e);
					}
				}
			}
		};
		currentSearchWorker.execute();
	}

	private List<String> searchItems(String query)
	{
		List<String> matches = new ArrayList<>();

		try
		{
			// Use ItemManager's search which queries the wiki API
			List<ItemPrice> results = itemManager.search(query);

			// Convert to item names and limit to MAX_SUGGESTIONS
			for (int i = 0; i < Math.min(results.size(), MAX_SUGGESTIONS); i++)
			{
				if (Thread.currentThread().isInterrupted())
				{
					break;
				}

				ItemPrice itemPrice = results.get(i);
				String itemName = itemPrice.getName();
				if (itemName != null && !itemName.equals("null"))
				{
					matches.add(itemName);
				}
			}
		}
		catch (Exception e)
		{
			log.error("Error searching items with query: {}", query, e);
		}

		return matches;
	}

	private void showSuggestions(List<String> items)
	{
		suggestionPopup.removeAll();

		if (items.isEmpty())
		{
			suggestionPopup.setVisible(false);
			return;
		}

		// Create menu items for each suggestion
		for (String itemName : items)
		{
			JMenuItem menuItem = new JMenuItem(itemName);
			menuItem.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			menuItem.setForeground(Color.WHITE);
			menuItem.setOpaque(true);

			// Highlight on hover
			menuItem.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseEntered(java.awt.event.MouseEvent e)
				{
					menuItem.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				}

				@Override
				public void mouseExited(java.awt.event.MouseEvent e)
				{
					menuItem.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				}
			});

			// Set item name when clicked
			menuItem.addActionListener(e ->
			{
				searchField.setText(itemName);
				suggestionPopup.setVisible(false);
				if (onItemSelected != null)
				{
					onItemSelected.accept(itemName);
				}
			});

			suggestionPopup.add(menuItem);
		}

		// Position popup below search field
		suggestionPopup.setPreferredSize(new Dimension(searchField.getWidth(), Math.min(items.size() * 25, 250)));
		suggestionPopup.show(searchField, 0, searchField.getHeight());
	}

	/**
	 * Set callback for when item is selected
	 */
	public void setOnItemSelected(Consumer<String> callback)
	{
		this.onItemSelected = callback;
	}

	/**
	 * Get the current text in the search field
	 */
	public String getText()
	{
		return searchField.getText();
	}

	/**
	 * Set the text in the search field
	 */
	public void setText(String text)
	{
		searchField.setText(text);
	}

	/**
	 * Set placeholder text
	 */
	public void setPlaceholder(String placeholder)
	{
		searchField.setToolTipText(placeholder);
	}

	/**
	 * Get the underlying text field for additional customization
	 */
	public JTextField getTextField()
	{
		return searchField;
	}
}
