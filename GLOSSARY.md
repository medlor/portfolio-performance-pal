# Portfolio Transactions

The language used to describe securities, holdings, and the transactions that change them.

## Language

**Security**:
A financial instrument identified independently of purchases, sales, or the quantity currently held.
_Avoid_: Stock when referring to the instrument definition

**Holding**:
The quantity of a security held in a particular securities account at a specified time.
_Avoid_: Security when referring to owned quantity

**Holding last updated**:
The date and time of the latest quantity-changing transaction for a security in a particular securities account. Dividends and security metadata changes do not change this timestamp.

**Transaction history**:
The chronological record of transactions affecting an account or security, including purchases and sales that no longer contribute to current holdings.

**Securities account**:
An account holding quantities of securities and the transactions that change those quantities.
_Avoid_: Account without qualification when cash accounts are also being discussed

**Cash account**:
An account recording cash debits and credits, including purchase payments, sale proceeds, and dividends.
_Avoid_: Account without qualification when securities accounts are also being discussed

**Dividend**:
A cash distribution associated with a security, recorded as a credit to a cash account with any applicable fees and taxes.

**Purchase**:
A transaction recording the acquisition of a quantity of shares in a security. Purchases remain part of the history after some or all of those shares are sold.
_Avoid_: Add stock

**Sale**:
A transaction recording the disposal of some or all held shares in a security. A sale preserves the historical purchases and earlier sales.
_Avoid_: Remove stock, delete stock

**FIFO (first in, first out)**:
The rule that matches sold shares to the earliest remaining purchases first.
