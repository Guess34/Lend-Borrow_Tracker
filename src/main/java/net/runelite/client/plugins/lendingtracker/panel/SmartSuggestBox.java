package net.runelite.client.plugins.lendingtracker.panel;

import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.List;
import java.util.stream.Collectors;

public class SmartSuggestBox extends JTextField {
    private final ItemManager itemManager;
    private JPopupMenu suggestionPopup;
    private List<ItemPrice> suggestions;
    private boolean isUpdatingFromSuggestion = false;
    
    public SmartSuggestBox(ItemManager itemManager) {
        this.itemManager = itemManager;
        this.suggestionPopup = new JPopupMenu();
        
        getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { 
                if (!isUpdatingFromSuggestion) updateSuggestions(); 
            }
            
            @Override
            public void removeUpdate(DocumentEvent e) { 
                if (!isUpdatingFromSuggestion) updateSuggestions(); 
            }
            
            @Override
            public void changedUpdate(DocumentEvent e) { 
                if (!isUpdatingFromSuggestion) updateSuggestions(); 
            }
        });
    }
    
    private void updateSuggestions() {
        String text = getText();
        if (text.length() < 2) {
            suggestionPopup.setVisible(false);
            return;
        }
        
        suggestions = itemManager.search(text).stream()
            .limit(10)
            .collect(Collectors.toList());
        
        if (suggestions.isEmpty()) {
            suggestionPopup.setVisible(false);
            return;
        }
        
        suggestionPopup.removeAll();
        
        for (ItemPrice item : suggestions) {
            JMenuItem menuItem = new JMenuItem(item.getName() + " - " + item.getPrice() + " gp");
            menuItem.addActionListener(e -> {
                isUpdatingFromSuggestion = true;
                setText(item.getName());
                isUpdatingFromSuggestion = false;
                suggestionPopup.setVisible(false);
            });
            suggestionPopup.add(menuItem);
        }
        
        suggestionPopup.show(this, 0, getHeight());
    }
}