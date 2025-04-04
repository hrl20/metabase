// eslint-disable-next-line no-restricted-imports
import styled from "@emotion/styled";

import { color } from "metabase/lib/colors";

export const SectionTitle = styled.h3`
  font-weight: bold;
  color: var(--mb-color-text-light);
  font-size: 0.875rem;
  margin-bottom: 0.5rem;
`;

export const ColorText = styled.div<{ color: string }>`
  margin-top: 1rem;
  color: ${(props) => color(props.color)};
`;

export const PaddedForm = styled.form`
  padding: 0 1rem;
  color: var(--mb-color-text-medium);
`;
