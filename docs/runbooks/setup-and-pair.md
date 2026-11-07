# Set up a DeKoi (for owners)

You need: a Raspberry Pi 4 or 5 with its power supply, a memory card of 8 GB
or more, a card reader for your computer, a network cable, and an Android phone.

1. Install the DeKoi app on your phone and sign in with Google.
2. On your computer, open DeKoi Setup and insert the memory card.
3. Choose the card, then choose "Cable" (recommended) or enter your Wi-Fi
   name and password. Confirm, and wait until DeKoi Setup shows a code.
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

## See your decoy

On your network, your DeKoi looks like a family storage server called
family-nas. That is the bait, and you can look at it yourself:

- Windows: open File Explorer and choose Network. FAMILY-NAS is listed under
  Computer. If Explorer asks to turn on network discovery, allow it for
  private networks.
- Mac: open Finder. family-nas is listed under Network in the sidebar.
- Any computer or phone: open http://family-nas.local in a browser to see its
  sign-in page.

Seeing it in the list does not alert you. Opening family-nas, signing in, or
opening a file in its Public folder does, within about a minute, as it would
for an intruder. That is a good way to check that alerts reach your phone. If
it asks for a user name and password, make one up; never type a real one.
Afterwards you can mark your computer as known in the alert.

Cards prepared before this version keep the name backup-nas and do not show
up in Explorer or Finder. Prepare the card again with DeKoi Setup to get the
new decoy.
