# SMP-Auction 1.0.0 (author: im4theone)

Original auction house for Paper 1.21.11 (Java 21). Replaces the downloaded DonutAuctionHouse.

- `/ah` (aliases `/auction`, `/auctionhouse`): browse all listings. Sort (newest / ending soon / lowest / highest), filter
  (blocks, tools, combat, food, books, auctions only), search (item or seller), pages.
- Sell: `/ah sell <price>` (item in hand, 48 h), `/ah sell <price> auction`, `/ah auction <start>`, or `/ah sell` for the screen
  where you pick price, length (6/12/24/48/72/168 h) and Buy It Now or auction. Prices: `500`, `2.5k`, `1.5m`.
- Buy It Now: click, confirm. The item goes to your collection box (`/ah collect`). The seller gets the price minus the
  sales tax (5%) straight away, online or not.
- Auctions: the bid is taken at once; the outbid player gets theirs back at once. Minimum raise 5% or $1. A bid in the last 30
  seconds extends the auction to 30 s. When it ends the highest bidder wins; with no bids the item goes back to the seller.
- `/ah mine` (shift-click to cancel; an auction with bids cannot be cancelled), `/ah history`, `/ah collect`.
- Limits: 5 live listings, raised by `smpauction.limit.10|25|50|100|unlimited`. Blacklist, blocked game modes and worlds,
  optional listing fee. Admin (`smpauction.admin`): `/ah reload | info | cancel <number>`.
- Safety: money is taken first and the change saved second; payouts are saved before they are paid (a crash can lose a payout,
  never pay twice); an unconfirmed payment is never retried; a failed payout is kept and retried. An item exists in exactly one
  place at all times (listing, collection box or inventory). A damaged `auction.yml` stops the plugin instead of being overwritten.

The old DonutAuctionHouse database held one $10 test listing; it is not imported.
Tests: `./gradlew build` (19 tests); `boot-test.sh` boots a throwaway server.
