import { useEffect, useState } from "react";
import { ApiError } from "../auth/api";

function message(error: unknown) {
  return error instanceof ApiError || error instanceof Error ? error.message : "The request failed";
}

export function useLoad<T>(loader: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    let live = true;
    setLoading(true);
    loader().then((value) => { if (live) setData(value); }).catch((caught) => { if (live) setError(message(caught)); }).finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return { data, error, loading };
}
