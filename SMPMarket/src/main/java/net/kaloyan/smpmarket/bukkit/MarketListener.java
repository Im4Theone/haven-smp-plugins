package net.kaloyan.smpmarket.bukkit;

import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kaloyan.smpmarket.command.Messages;
import net.kaloyan.smpmarket.model.MarketTx;
import net.kaloyan.smpmarket.service.MarketService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Tells joining players about items waiting for them and sales they have not heard about. Read-only apart from
 * remembering that a sale was announced; never delivers anything by itself.
 */
public final class MarketListener implements Listener {

    private final MarketService service;
    private final Messages messages;
    private final Executor mainThread;
    private final Logger logger;

    /**
     * @param service    the market
     * @param messages   message sender
     * @param mainThread runs the reporting on the main thread
     * @param logger     console logger
     */
    public MarketListener(MarketService service, Messages messages, Executor mainThread, Logger logger) {
        this.service = service;
        this.messages = messages;
        this.mainThread = mainThread;
        this.logger = logger;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        service.onJoin(player.getUniqueId()).whenComplete((summary, error) -> mainThread.execute(() -> {
            if (error != null) {
                logger.log(Level.WARNING, "Could not load market notifications for " + player.getName(), error);
                return;
            }
            if (!player.isOnline()) {
                return;
            }
            for (MarketTx sale : summary.unnotifiedSales()) {
                long net = sale.priceMinor() - sale.feeMinor();
                messages.info(player, "While you were away, your listing #" + sale.listingId() + " sold to "
                        + sale.buyerName() + " for " + service.format(sale.priceMinor())
                        + (sale.feeMinor() > 0 ? " (you receive " + service.format(net) + " after the fee)" : "") + ".");
            }
            if (!summary.unnotifiedSales().isEmpty()) {
                service.markSalesNotified(summary.unnotifiedSales());
            }
            if (summary.pendingDeliveries() > 0) {
                messages.info(player, "You have " + summary.pendingDeliveries()
                        + " item(s) waiting. Use /market collect.");
            }
        }));
    }
}
