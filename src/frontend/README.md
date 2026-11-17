# BOSS Platform Admin

This is the internal Platform Admin React/TypeScript/Vite application. It has its own Firebase Auth initialization and sends authenticated requests only to the dedicated `boss-admin-api` base path (`/admin/api/v1`). It does not import customer-app authentication or demo customer data.

## Local development

1. Copy `.env.example` to `.env.local`.
2. Set the web configuration values from the separate Platform Admin Firebase project. Never use the customer Firebase project configuration.
3. Keep `VITE_PLATFORM_ADMIN_API_BASE_URL` pointed at the Platform Admin service, ending in `/admin/api/v1`.
4. Run `npm install`, `npm run dev`, and open the local Vite URL.

The API is authoritative. When configuration or the API is unavailable, the app displays an explicit setup or error state rather than showing seeded customers or simulated success.

## Contract and validation

`npm run generate:api` regenerates the TypeScript API types and enum options from `BOSS_PRD_AND_BACKLOG_v1.1.3/10_API_CONTRACTS/openapi/platform-admin.yaml`. Do not edit the generated file directly. The contract does not enumerate `planTier` values, so the create form uses the contract's `pilot` default and leaves the string editable instead of inventing a plan list.

Run the focused checks with `npm test` and `npm run build`.

## One-time invitation tokens

Invitation tokens are held only in React memory for the immediate handoff and are cleared when the handoff is closed or the result page is left. The app does not write tokens to browser storage, logs, analytics, or telemetry. Do not add persistence or diagnostic logging for these values.
