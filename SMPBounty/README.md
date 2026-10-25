# SMP-Bounty 1.0.0 (author: Kaloyan)
`/bounty set <player> <amount>` puts money on a player's head (minimum 1,000, 10% tax on top; 5000, 2.5k, 1m work). The money is taken at once and held. Whoever kills the player
(not someone who added money to it) collects the whole bounty; it is announced to everybody. `/bounty list` shows the biggest bounties, `/bounty check <player>` one player's.
Payments are saved before they are paid (a crash can lose a payout, never pay twice); a failed payout is kept and retried; an unconfirmed one is never retried.
