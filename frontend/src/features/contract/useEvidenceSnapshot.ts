import {useRef} from 'react';

/** Polling may recreate arrays and callbacks without changing a citation.
 * Keep that same source snapshot mounted; real locator/content changes still reload. */
export function useEvidenceSnapshot<T>(value:readonly T[]):readonly T[]{
  const key=JSON.stringify(value);
  const snapshot=useRef({key,value});
  if(snapshot.current.key!==key)snapshot.current={key,value};
  return snapshot.current.value;
}
