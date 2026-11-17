export type RemoteState = "loading" | "empty" | "error";

export type RemoteList = {
  status: RemoteState;
  message: string;
};

const EMPTY = "No records are available. This screen does not show demo business data.";
const ERROR = "The customer API did not return records. Nothing was invented to fill this screen.";
const LOADING = "Loading records from the customer API.";

export function loadList(requested: string | null): RemoteList {
  if (requested === "loading") return { status: "loading", message: LOADING };
  if (requested === "error") return { status: "error", message: ERROR };
  return { status: "empty", message: EMPTY };
}

export const SAVE_BLOCKED = "This action is not connected to the customer API. Nothing was saved.";
