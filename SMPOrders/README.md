# SMP-Orders 1.0.0 (author: Kaloyan)

Original buy-order marketplace for Paper 1.21.11 (Java 21). Replaces the downloaded DonutOrders.

- `/order` (alias `/orders`, optionally `/order diamond`): all open orders. Sort (recent / most paid / most delivered / most per
  item), filter, search, pages. Click an order to deliver items to it; click your own to manage it.
- New order: pick the item (list, or click with an item on your cursor), enchantments for enchantable items, amount and price
  per item (typed in chat: `64`, `1k`, `2.5`, `1.5m`). The money for ALL items plus a creation tax (default 5%, not refunded) is
  taken at once and held (escrow).
- Deliver: put matching items (same item, same enchantments, not renamed, not damaged, no shulker contents) into the free slots
  and confirm. You are paid at once from the buyer's escrow. The buyer collects the items from "Your orders".
- Cancel: refunds the price of what was not delivered. Orders expire after 7 days (refunded the same way).
- `/ordersadmin reload | info | cancel <number>` (`smporders.admin`).
- Safe money handling: a buyer's money is taken first, the order saved second; a payout is written off the owed list and saved
  first and paid second (a crash can lose a payout but never pays twice); a payment the economy did not confirm is never
  retried; failed payouts are kept and retried every minute. Offline players are paid and refunded too.
- Data: `orders.yml` (atomic writes; a damaged file stops the plugin instead of being overwritten).

Old DonutOrders data was kept only in memory, so there is nothing to import.
Tests: `./gradlew build` (24 tests); `boot-test.sh` boots a throwaway server.
