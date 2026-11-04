import { useEffect, useState } from "react";
import {
  CURRENT_F02_CONTRACT_IDS,
  type ContractOverviewItem,
  type CurrentF02ContractId,
} from "./contractOverviewTypes";
import {
  contractDataSource,
  type ContractDataSource,
} from "./data/contractDataSource";
import { resolveContractApiId } from "./data/contractSourceMap";

function createLoadingItem(displayId: CurrentF02ContractId): ContractOverviewItem {
  return {
    displayId,
    apiId: resolveContractApiId(displayId),
    contractState: "LOADING",
    contract: null,
    reviewState: "LOADING",
    reviewResult: null,
  };
}

async function loadOverviewItem(
  displayId: CurrentF02ContractId,
  dataSource: ContractDataSource,
  signal: AbortSignal,
): Promise<ContractOverviewItem> {
  const item = createLoadingItem(displayId);
  if (!item.apiId) {
    return {
      ...item,
      contractState: "ERROR",
      reviewState: "ERROR",
    };
  }

  const contractResult = await Promise.resolve()
    .then(() => dataSource.getContract(displayId, { signal }))
    .then(
      (value) => ({ status: "fulfilled" as const, value }),
      (reason: unknown) => ({ status: "rejected" as const, reason }),
    );

  return {
    ...item,
    contractState: contractResult.status === "fulfilled" ? "LOADED" : "ERROR",
    contract: contractResult.status === "fulfilled" ? contractResult.value : null,
    // Candidate loading intentionally does not read contract-level /agent-results.
    reviewState: "LOADED",
    reviewResult: null,
  };
}

export interface ContractOverviewState {
  items: ContractOverviewItem[];
}

export function useContractOverview(
  dataSource: ContractDataSource = contractDataSource,
): ContractOverviewState {
  const [items, setItems] = useState<ContractOverviewItem[]>(() =>
    CURRENT_F02_CONTRACT_IDS.map(createLoadingItem),
  );

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    setItems(CURRENT_F02_CONTRACT_IDS.map(createLoadingItem));

    void Promise.allSettled(
      CURRENT_F02_CONTRACT_IDS.map((displayId) =>
        loadOverviewItem(displayId, dataSource, controller.signal),
      ),
    ).then((settledItems) => {
      if (!active || controller.signal.aborted) return;
      setItems(settledItems.map((settledItem, index) => {
        if (settledItem.status === "fulfilled") return settledItem.value;
        return {
          ...createLoadingItem(CURRENT_F02_CONTRACT_IDS[index]),
          contractState: "ERROR",
          reviewState: "ERROR",
        };
      }));
    });

    return () => {
      active = false;
      controller.abort();
    };
  }, [dataSource]);

  return { items };
}
