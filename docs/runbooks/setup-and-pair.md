# Set up a DeKoi (for owners)

You need: a Raspberry Pi 4 or 5 with its power supply, a memory card of 8 GB
or more, a card reader for your computer, a network cable, and an Android phone.

1. Install the DeKoi app on your phone and sign in with Google.
2. On your computer, insert the memory card, open DeKoi Setup and choose
   Start.
3. Choose the card, then choose "Network cable" (recommended) or enter your
   Wi-Fi name and password. Confirm, and wait until DeKoi Setup shows a code.
4. In the app, tap "Add DeKoi" and scan the code on your computer screen.
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

On your network, your DeKoi looks like a family storage server called
family-nas, with shared folders full of tempting files. All of it is bait:
the passwords open nothing, and the card and personal numbers are test
numbers. You can play the intruder yourself:

1. Find it.
   - Windows: open File Explorer, then Network, then FAMILY-NAS. Or type
     `\\family-nas` in the address bar. If Explorer asks to turn on network
     discovery, allow it for private networks.
   - Mac: choose family-nas under Network in Finder's sidebar. Or choose
     Go, then Connect to Server, and enter `smb://family-nas.local`; connect
     as a guest.
2. Open the Public folder and open a file, for example `passord.txt` or
   `kredittkort-kopi.pdf`.
3. Try to open a locked folder, such as Finance. When it asks you to sign
   in, use a user name and password from the files, for example
   `familie.admin`. The alert then says the name came from the bait.
4. Open http://family-nas.local in a browser and sign in there too.
5. Watch the alerts arrive on your phone, within about a minute.

Seeing family-nas in the list does not alert you; opening it does, as it
would for an intruder. Never type a real password. Afterwards you can mark
your computer as known in the alert.

Cards prepared before this version keep the name backup-nas and do not show
up in Explorer or Finder. Prepare the card again with DeKoi Setup to get the
new decoy.
