import styled from "@emotion/styled";

import Button from "metabase/core/components/Button";
import { alpha, color } from "metabase/lib/colors";

export interface OptionRootProps {
  isSelected?: boolean;
}

const getOptionIconColor = ({ isSelected }: OptionIconContainerProps) => {
  if (isSelected) {
    return color("text-white");
  } else {
    return color("brand");
  }
};

export const OptionRoot = styled.div<OptionRootProps>`
  padding: 0.5rem;
  width: 25%;
  text-align: center;

  ${props =>
    props.isSelected &&
    `
    ${OptionIconContainer} {
      &, &:hover {
      background-color: var(--mb-color-brand);
      color: ${getOptionIconColor(props)};
      border: 1px solid transparent;
      }
    }

    ${OptionText} {
      color: var(--mb-color-brand);
    }
  `}
`;

export interface OptionIconContainerProps {
  isSelected?: boolean;
}

export const OptionText = styled.div`
  margin-top: 0.5rem;
  color: var(--mb-color-text-medium);
  font-weight: bold;
  font-size: 0.75rem;
`;

export const SettingsButton = styled(Button)`
  position: absolute;
  top: -0.5rem;
  right: -0.75rem;
  padding: 0.375rem;
  border: 1px solid var(--mb-color-border);
  border-radius: 50px;
  background-color: var(--mb-color-bg-white);
  opacity: 0;
`;

export const OptionIconContainer = styled.div<OptionIconContainerProps>`
  position: relative;
  display: inline-flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  color: ${getOptionIconColor};
  background-color: ${props => props.isSelected && color("brand")};
  border-radius: 100%;
  border: 1px solid var(--mb-color-border);
  cursor: pointer;
  padding: 0.875rem;

  &:hover {
    color: var(--mb-color-brand);
    background-color: ${() => alpha("brand", 0.15)};
    border: 1px solid transparent;

    ${SettingsButton} {
      opacity: 1;
    }
  }
`;
