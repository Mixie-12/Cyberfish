# Set up a DeKoi (for owners)

You need: a Raspberry Pi 4 or 5 with its power supply, a memory card of 8 GB
or more, a card reader for your computer, a network cable, and an Android phone.

1. Install the DeKoi app on your phone and sign in with Google.
2. On your computer, insert the memory card, open DeKoi Setup and choose
   Start.
3. Choose the card, then choose "Network cable" (recommended) or enter your
   Wi-Fi name and password. Confirm, and wait until DeKoi Setup shows a code.
4. In the app, tap "Add a DeKoi" and scan the code on your computer screen.
   The app shows the same short name as DeKoi Setup, for example "DeKoi 7F3K".
5. Put the card in your DeKoi. Connect the network cable to your router, then
   the power.
6. Wait. The first start takes up to 10 minutes. The app shows each step and
   says "You're protected" when DeKoi is watching your network.

If the app still says "Setting up" after 20 minutes, it shows a short checklist.
If nothing helps, prepare the card again with DeKoi Setup and start over; the
old entry can be removed in the app.

DeKoi updates itself. You never need to log in to it, and there is nothing to
configure on the device.

## Demo your DeKoi

On your network, your DeKoi looks like the central server of a small
company, Kaldskjær Sjømat AS, called sap-pay-prod: payments, SAP, payroll,
backups and the cold store's control system, with shared folders full of
tempting files. The company is made up and all of it is bait: the
passwords open nothing, and no card, account or personal number in the
files can be real. You can play the intruder yourself:

1. Find it.
   - Windows: open File Explorer, then Network, then SAP-PAY-PROD. Or type
     `\\sap-pay-prod` in the address bar. If Explorer asks to turn on
     network discovery, allow it for private networks.
   - Mac: choose sap-pay-prod under Network in Finder's sidebar. Or choose
     Go, then Connect to Server, and enter `smb://sap-pay-prod.local`;
     connect as a guest.
2. Open the Felles folder and open a file, for example
   `IT\VPN_access.txt` or `Finance\Card settlement\Chargebacks_2026-09-30_kortdata.xlsx`.
3. Try to open a locked folder, such as Finance. When it asks you to sign
   in, use a user name and password from the files, for example
   `ks-drift`. The alert then says the name came from the bait.
4. Open http://sap-pay-prod.local in a browser and sign in to the payment
   portal too.
5. Watch the alerts arrive on your phone, within about a minute.

Seeing sap-pay-prod in the list does not alert you; opening it does, as it
would for an intruder. Never type a real password. Afterwards you can mark
your computer as known in the alert.

A DeKoi whose card was prepared before this version shows the same decoy
after its next update, but your router may still list it as family-nas,
and the SAP, Modbus, S7 and SQL Server decoys stay closed. Prepare the card
again to change both. Cards older still keep the name backup-nas and do
not show up in Explorer or Finder.
