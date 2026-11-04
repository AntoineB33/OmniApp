# Your own OmniApp server

OmniApp works on one device with no server at all. To sync between your devices, back up to the cloud, and use every
feature (the Supabase usage panel, the forgetting of old history near the limit, the phone's pause-end cue), you run
your **own free Supabase project**, and point the app at it.

This guide is for someone comfortable with a terminal. It takes about 30 minutes the first time. It costs nothing: the
app keeps itself under a tenth of every limit of Supabase's free plan (`docs/invariants/server-quota.md`).

> The scripts are Windows `.bat` files. On macOS or Linux, run the same `supabase` commands they run by hand (step 7
> lists them).

## What to know first

- **Your server is yours alone.** Nobody else, the app's author included, can see what is on it. You also look after
  it: when an update of the app brings a database change, you deploy it (step 11). The app tells you when.
- **A free project is paused after a week without activity.** Supabase pauses free projects nobody used for 7 days. The
  app then works offline. Your data isn't lost: restore the project from its page in the Supabase dashboard, and the
  app syncs again.
- **The secrets in this guide stay on your computer.** The database password and the `service_role` key give full
  control of your project. They go in `scripts\accounts.env`, which git never commits. The app itself only ever holds
  the project's **public** key.

## 1. What you need

- Windows 10 or 11.
- [Git](https://git-scm.com/download/win).
- A JDK, version 17 or newer (21 recommended), for example [Eclipse Temurin](https://adoptium.net/).
- [Node.js](https://nodejs.org/) (LTS). Optional: the Supabase CLI is then installed with npm; without it, the script
  downloads it instead.
- A free [Supabase](https://supabase.com/) account.

## 2. Create the Supabase project

1. In the [Supabase dashboard](https://supabase.com/dashboard), create a **new project** on the free plan.
2. Pick any name, and the region closest to you.
3. Choose a **database password**, and keep it: step 6 needs it.
4. Wait until the project is ready (a minute or two).

## 3. Change two authentication settings

In your project, open **Authentication**, then its sign-in settings (**Sign In / Providers**):

- **Email**: turn **Confirm email** OFF. The app signs you in with a username it turns into an address, and that
  address never receives mail.
- **Allow anonymous sign-ins**: turn it ON. The app gives every new device a guest account before you sign in.

Save.

## 4. Get the app's code

In a terminal:

```bat
git clone https://github.com/AntoineB33/OmniApp.git
cd OmniApp
```

## 5. Install the Supabase CLI

```bat
scripts\update-supabase-cli.bat
```

Then open a **new** terminal (so it finds the CLI), go back to the `OmniApp` folder, and sign in once:

```bat
supabase login
```

A browser page asks you to authorize the CLI.

## 6. Fill `scripts\accounts.env`

Copy `scripts\accounts.env.example` to `scripts\accounts.env`, open it, and set these four lines (remove their `#`):

| Line | Where to find it |
| --- | --- |
| `SUPABASE_PROJECT_REF=` | Your project's URL is `https://<ref>.supabase.co`: the `<ref>` part. Also under **Project Settings → General**. |
| `SUPABASE_DB_PASSWORD=` | The database password from step 2. |
| `SUPABASE_SERVICE_ROLE_KEY=` | **Project Settings → API Keys** → the `service_role` key. If the page has a **Legacy API keys** tab, use the long `eyJ…` key there, not a new `sb_secret_…` one. |
| `EDGE_BASE_URL=` | `https://<ref>.functions.supabase.co` |

The `ACC1_…` to `ACC3_…` lines are for the developer's test accounts: you can leave them as they are.

The `service_role` key is **not** optional any more: the deploy uses it to schedule the server's daily clean-up jobs
(old tombstones, the job log, and forgetting the oldest history near the limit). Without it, the database grows
until it reaches the limit.

## 7. Deploy the server

```bat
scripts\deploy-supabase.bat
```

It runs three steps, and ends on `[OK]`:

1. `supabase db push`: creates the tables and functions (`supabase/migrations/`).
2. `supabase functions deploy pause-cue` and `pause-cue-cron`: the two Edge Functions of the pause-end cue.
3. `supabase/pause-cue-setup.sql`: the scheduled jobs. Check this step says it applied the setup, not "Skipping".

Every step can be run again safely. If it fails, the error is printed above the red box; fix it and run it again.

On macOS or Linux, run instead: `supabase link --project-ref <ref>`, `supabase db push`, `supabase functions deploy
pause-cue`, `supabase functions deploy pause-cue-cron`, then paste `supabase/pause-cue-setup.sql` into the dashboard's
**SQL Editor** after replacing its two placeholders (`__EDGE_BASE_URL__`, `__SERVICE_ROLE_KEY__`) with your values.

## 8. Build and start the app

```bat
gradlew :desktopApp:run
```

The first build downloads its tools and takes several minutes.

## 9. Point the app at your server

1. In the app, open the **Settings** page (the page button at the top of the left-side menu).
2. In **Server**, enter:
   - **Project URL**: `https://<ref>.supabase.co` (**Project Settings → API** or **Data API**: "Project URL");
   - **Public (anon) key**: the `anon` / `public` key, on the same page as the `service_role` key. Never the
     `service_role` key: that one stays in `accounts.env`.
3. Press **Use this server**, then **restart the app**.

Until the restart the app sends nothing. After it, the device creates its guest account on your server.

> Do this first, on a fresh install. A device that already used another server keeps its local data, but its account
> stays on that other server: on yours it starts a new one.

## 10. Check that it works

On the **Settings** page:

- **Server** says "Your server: https://<ref>.supabase.co" and "It has everything this version of the app needs".
- In **Supabase free tier**, press **Refresh**: it shows the database's size.

The project's egress (all devices), Realtime messages and Edge Function calls are not readable by the app: the
panel's link opens Supabase's Usage page, where you pick your project to see them.

To use the same data on another device, sign in there with the same account (the **Online** window), after pointing
that device at your server too.

## 11. When the app is updated

```bat
git pull
scripts\deploy-supabase.bat
gradlew :desktopApp:run
```

Running the deploy after every update is harmless: it only applies what is new. If you forget, the **Server** section
says "It needs an update: run scripts\deploy-supabase.bat against it", with the version the server has and the one the
app needs.

## 12. The phone (optional)

Build the Android app (`gradlew :androidApp:assembleDebug`, which needs the Android SDK: see the README), install it,
and set **Server** on the phone's Settings page the same way.

The phone's pause-end voice cue also needs Firebase (Android) or APNs (iOS) credentials set as Edge Function secrets:
see [`PAUSE_CUE_DELIVERY.md`](PAUSE_CUE_DELIVERY.md). Everything else works without them.

## When something goes wrong

| The app says | What to do |
| --- | --- |
| "not read: the server has no project_usage yet", or "It needs an update" | Run `scripts\deploy-supabase.bat` (step 7). |
| "not read: the server did not answer" | The project may be paused (open it in the dashboard and restore it), or the device is offline. |
| "not read: no account is signed in on this device" | Go online (the **Online** window); a guest account is created at the next start. |
| "Restart the app to use the server chosen" | Restart: the server chosen is used from the next start. |
| A sign-in that never completes | Step 3: **Confirm email** must be OFF. |
| A new device stays "signed out" | Step 3: **anonymous sign-ins** must be ON. |
| `deploy-supabase.bat` says the CLI is not on PATH | Step 5, then open a new terminal. |

`docs/SCRIPTS.md` documents every script and its gotchas.
