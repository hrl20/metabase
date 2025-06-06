import type {
  CollectionId,
  CollectionItem,
  PaginationRequest,
  PaginationResponse,
} from "metabase-types/api";
import type { SortingOptions } from "metabase-types/api/sorting";

export type StaleCollectionItem = CollectionItem & {
  last_used_at: string;
};

export type ListStaleCollectionItemsSortColumn = "name" | "last_used_at";

export type ListStaleCollectionItemsRequest = {
  id: CollectionId;
  before_date?: string;
  is_recursive?: boolean;
} & PaginationRequest &
  Partial<SortingOptions<ListStaleCollectionItemsSortColumn>>;

export type ListStaleCollectionItemsResponse = {
  data: StaleCollectionItem[];
} & PaginationResponse;
