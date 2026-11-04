import { request } from "../../../api/client";

export interface ContractSummary {
  id: string;
  contractNo: string;
  contractName: string;
  contractType: string;
  partyA: string;
  partyB: string;
  partyBCreditCode: string | null;
  contractAmount: number;
  amountInWords: string | null;
  status: string;
  versionNo: number;
  createdAt: string;
  updatedAt: string;
}

export interface PagedContractSummary {
  items: ContractSummary[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface ContractCatalogDataSource {
  listContracts(input: {
    page: number;
    size: number;
    status?: string;
    keyword?: string;
    signal?: AbortSignal;
  }): Promise<PagedContractSummary>;
}

class HttpContractCatalogDataSource implements ContractCatalogDataSource {
  listContracts(input: {
    page: number;
    size: number;
    status?: string;
    keyword?: string;
    signal?: AbortSignal;
  }): Promise<PagedContractSummary> {
    return request("/api/v1/contracts", {
      signal: input.signal,
      params: {
        page: String(input.page),
        size: String(input.size),
        ...(input.status ? { status: input.status } : {}),
        ...(input.keyword?.trim() ? { keyword: input.keyword.trim() } : {}),
      },
    });
  }
}

export const contractCatalogDataSource: ContractCatalogDataSource =
  new HttpContractCatalogDataSource();
