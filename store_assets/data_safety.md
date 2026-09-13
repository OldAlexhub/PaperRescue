# Google Play Data Safety Form — Answers for PaperRescue

Use this as a worksheet when filling in Play Console → App content → Data safety. Answers reflect that PaperRescue has no backend and the only data flow is the Google Mobile Ads SDK.

## Does your app collect or share any of the required user data types?
**Yes** — via the bundled Google Mobile Ads SDK (AdMob) only. PaperRescue's own code collects nothing.

## Data types to declare

| Category | Data type | Collected? | Shared? | Purpose |
|---|---|---|---|---|
| Device or other IDs | Advertising ID | Yes (by AdMob SDK) | Yes (with ad networks via Google) | Advertising or marketing |
| App activity | App interactions | Yes (by AdMob SDK) | Yes | Advertising or marketing, Analytics |
| App info and performance | Crash logs / diagnostics | Yes (by AdMob SDK) | Yes | Analytics |
| Location | Approximate location | Only if device settings permit; handled by AdMob SDK | Yes | Advertising or marketing |

Do **not** declare: personal identifiers (name, email, phone), photos/documents, files and docs, contacts, or any category tied to the actual scanned content — PaperRescue never transmits scanned documents, page images, OCR text, or quality scores anywhere. They exist only in local app storage.

## Is all of the user data collected encrypted in transit?
Yes — handled by the Google Mobile Ads SDK over standard HTTPS.

## Do you provide a way for users to request that their data be deleted?
Yes, functionally: because no PaperRescue-side data collection occurs, there is nothing to delete on our end. In-app, users can permanently delete all locally stored documents via **Settings → Clear Local Data**. For advertising identifiers, direct users to their device's Android Settings → Privacy → Ads controls (reset/delete advertising ID, opt out of personalization).

## Data collection is required or optional?
Optional in the sense that ad personalization can be limited via device-level settings, but ad delivery itself (non-personalized) is required for the app's ad-supported free model to function.

## Security practices
- No user data is transmitted to PaperRescue's own servers — there are none.
- All scanned content is stored in Android app-private storage, which is sandboxed per-app by the OS and removed on uninstall.
- Advertising data flows are handled entirely by the Google Mobile Ads SDK per Google's own security and privacy practices.

## Independent security review
Not applicable — no proprietary backend exists to review.

## Notes for the person filling this out in Play Console
1. Under **App content → Ads**, declare "Yes, my app contains ads."
2. Under **App content → Data safety**, walk through the "Advertising or marketing" and "Analytics" purposes and attach them to the Advertising ID / App interactions / Diagnostics rows above.
3. Leave Photos/Videos, Files/Docs, Personal Info, Messages, and Contacts categories unchecked — PaperRescue does not collect these even though it processes photos locally, because "collection" per Play's definition means transmission off-device, which never happens here.
4. Re-verify this worksheet whenever the AdMob SDK version changes, since Google periodically updates what the SDK reports collecting.
