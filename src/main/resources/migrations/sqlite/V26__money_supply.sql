-- SPEC 21.4 Class G, the money supply snapshot, SQLite.
--
-- "The plugin must be able to answer, at any moment, 'how much money exists and where did it come
-- from.' Without this you cannot detect an exploit you did not predict."
--
-- This table holds the STOCKS only -- what exists right now, in three places. The FLOWS SPEC 21.4
-- also asks for ("sum of all income by ledger type for the hour, and sum of all sinks") are
-- deliberately NOT copied here: the ledger already records every one of them, SPEC 3.6 makes it
-- append-only, and SPEC 1.5 makes it authoritative. A second copy could disagree with the first,
-- and the one that a fraud investigation would be reading is the copy. Same reasoning M21 used
-- when it declined to snapshot ledger rows into a report.
--
-- Stocks cannot be reconstructed from the ledger, which is why they need a table: a balance is the
-- sum of every row that ever touched it, and summing forty million rows to draw a graph is not a
-- query anybody runs twice.
CREATE TABLE money_supply (
    id             INTEGER  NOT NULL PRIMARY KEY AUTOINCREMENT,
    timestamp      BIGINT   NOT NULL,
    player_total   INTEGER  NOT NULL,   -- minor units, every wallet
    treasury_total INTEGER  NOT NULL,   -- minor units, every live city treasury
    escrow_total   INTEGER  NOT NULL    -- minor units, war wagers and open bounties
);

CREATE INDEX idx_money_supply_time ON money_supply (timestamp);
