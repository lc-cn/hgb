import { createContext, useContext } from "react";
import type { Account } from "../api/types";
import type { ConsoleData } from "./console";

/** What every page reads: the live data, who is signed in, and how to leave. */
export interface ConsoleContextValue { data: ConsoleData; account: Account; signedOut: () => void }
export const ConsoleContext = createContext<ConsoleContextValue | null>(null);
export function useConsole(): ConsoleContextValue {
  const value = useContext(ConsoleContext);
  if (!value) throw new Error("useConsole outside the console");
  return value;
}
