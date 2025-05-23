import noResultsSource from "assets/img/no_results.svg";
import type { IllustrationValue } from "metabase/plugins";
import { getSetting, getSettings } from "metabase/selectors/settings";
import type {
  EnterpriseSettings,
  IllustrationSettingValue,
} from "metabase-types/api";

import { LOADING_MESSAGE_BY_SETTING } from "../whitelabel/lib/loading-message";

import type { EnterpriseState } from "./types";

const DEFAULT_LOGO_URL = "app/assets/img/logo.svg";

const getCustomLogoUrl = (settingValues: EnterpriseSettings) => {
  return (
    settingValues["application-logo-url"] ||
    settingValues.application_logo_url ||
    DEFAULT_LOGO_URL
  );
};

export const getLogoUrl = (state: EnterpriseState) =>
  getCustomLogoUrl(getSettings(state));

export const getLoadingMessage = (state: EnterpriseState) => {
  const setting = getSetting(state, "loading-message");
  // default to empty string to account for a historical bug where the
  // setting could be set via MB_LOADING_MESSAGE to a value not in our enum
  return LOADING_MESSAGE_BY_SETTING[setting]?.value ?? (() => "");
};

// eslint-disable-next-line no-literal-metabase-strings -- This is a Metabase string we want to keep. It's used for comparison.
const DEFAULT_APPLICATION_NAME = "Metabase";
export const getIsWhiteLabeling = (state: EnterpriseState) =>
  getApplicationName(state) !== DEFAULT_APPLICATION_NAME;

export function getApplicationName(state: EnterpriseState) {
  return getSetting(state, "application-name");
}

export function getShowMetabaseLinks(state: EnterpriseState) {
  return getSetting(state, "show-metabase-links");
}

export function getLoginPageIllustration(
  state: EnterpriseState,
): IllustrationValue {
  const illustrationOption = getSetting(
    state,
    "login-page-illustration",
  ) as IllustrationSettingValue;

  switch (illustrationOption) {
    case "default":
      return {
        src: "app/img/bridge.svg",
        isDefault: true,
      };

    case "none":
      return null;

    case "custom":
      return {
        src: getSetting(state, "login-page-illustration-custom") as string,
        isDefault: false,
      };
  }
}

export function getLandingPageIllustration(
  state: EnterpriseState,
): IllustrationValue {
  const illustrationOption = getSetting(
    state,
    "landing-page-illustration",
  ) as IllustrationSettingValue;

  switch (illustrationOption) {
    case "default":
      return {
        src: "app/img/bridge.svg",
        isDefault: true,
      };

    case "none":
      return null;

    case "custom":
      return {
        src: getSetting(state, "landing-page-illustration-custom") as string,
        isDefault: false,
      };
  }
}

export function getNoDataIllustration(state: EnterpriseState): string | null {
  const illustrationOption = getSetting(
    state,
    "no-data-illustration",
  ) as IllustrationSettingValue;

  switch (illustrationOption) {
    case "default":
      return noResultsSource;

    case "none":
      return null;

    case "custom":
      return getSetting(state, "no-data-illustration-custom") as string;
  }
}

export function getNoObjectIllustration(state: EnterpriseState): string | null {
  const illustrationOption = getSetting(
    state,
    "no-object-illustration",
  ) as IllustrationSettingValue;

  switch (illustrationOption) {
    case "default":
      return noResultsSource;

    case "none":
      return null;

    case "custom":
      return getSetting(state, "no-object-illustration-custom") as string;
  }
}

export const getApplicationColors = (settingValues: EnterpriseSettings) =>
  settingValues["application-colors"];
