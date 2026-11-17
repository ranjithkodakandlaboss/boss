import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';

/**
 * Preserves originating list tab, filters, search query, and sort order
 * when navigating into and back out of record detail views or side sheets.
 */
type ListLocation = { pathname: string; search: string };

export function useReturnNavigation(defaultFallback: string) {
  const location = useLocation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const state = location.state as { from?: string; background?: ListLocation } | null;
  const stateFrom = state?.from;
  const queryFrom = searchParams.get('from');
  const originUrl = stateFrom || queryFrom || defaultFallback;
  const list = state?.background ?? (location.pathname === '/sales' ? { pathname: location.pathname, search: location.search } : undefined);

  const goBack = (preferredFallback?: string) => {
    const target = stateFrom || queryFrom || preferredFallback || defaultFallback;
    navigate(target);
  };

  const withOriginState = () => ({ state: { from: originUrl, ...(list ? { background: list } : {}) } });

  const openRecord = (path: string) => {
    navigate(path, { state: { from: list ? `${list.pathname}${list.search}` : originUrl, ...(list ? { background: list } : {}) } });
  };

  return { originUrl, goBack, withOriginState, openRecord };
}
