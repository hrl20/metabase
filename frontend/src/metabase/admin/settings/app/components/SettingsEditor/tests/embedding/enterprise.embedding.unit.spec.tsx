import { act, screen } from "__support__/ui";

import type { SetupOpts } from "./setup";
import {
  embeddingSettingsUrl,
  getQuickStartLink,
  goToStaticEmbeddingSettings,
  interactiveEmbeddingSettingsUrl,
  setupEmbedding,
  staticEmbeddingSettingsUrl,
} from "./setup";

const setupEnterprise = (opts?: SetupOpts) => {
  return setupEmbedding({
    ...opts,
    hasEnterprisePlugins: true,
    hasEmbeddingFeature: false,
  });
};

describe("[EE, no token] embedding settings", () => {
  describe("when the embedding is disabled", () => {
    describe("static embedding", () => {
      it("should not allow going to static embedding settings page", async () => {
        const { history } = await setupEnterprise({
          settingValues: { "enable-embedding": false },
        });

        expect(screen.getByRole("button", { name: "Manage" })).toBeDisabled();

        act(() => {
          history.push(staticEmbeddingSettingsUrl);
        });

        expect(history.getCurrentLocation().pathname).toEqual(
          embeddingSettingsUrl,
        );
      });

      it("should prompt to upgrade to remove the Powered by text", async () => {
        await setupEnterprise({
          settingValues: { "enable-embedding": false },
        });

        expect(screen.getByText("upgrade to a paid plan")).toBeInTheDocument();

        expect(
          screen.getByRole("link", { name: "upgrade to a paid plan" }),
        ).toHaveProperty(
          "href",
          "https://www.metabase.com/upgrade?utm_source=product&utm_medium=upsell&utm_content=embed-settings&source_plan=oss",
        );
      });
    });

    describe("interactive embedding", () => {
      it("should not allow going to interactive settings page", async () => {
        const { history } = await setupEnterprise({
          settingValues: { "enable-embedding": false },
        });

        act(() => {
          history.push(interactiveEmbeddingSettingsUrl);
        });

        expect(history.getCurrentLocation().pathname).toEqual(
          embeddingSettingsUrl,
        );
      });

      it("should have a learn more button for interactive embedding", async () => {
        await setupEnterprise({
          settingValues: { "enable-embedding": false },
        });
        expect(
          screen.getByRole("link", { name: "Learn More" }),
        ).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "Learn More" })).toHaveProperty(
          "href",
          "https://www.metabase.com/product/embedded-analytics?utm_source=oss&utm_media=embed-settings",
        );
      });

      it("should link to quickstart for interactive embedding", async () => {
        await setupEnterprise({
          settingValues: {
            "enable-embedding": false,
            version: { tag: "v1.49.3" },
          },
        });
        expect(getQuickStartLink()).toBeInTheDocument();
        expect(getQuickStartLink()).toHaveProperty(
          "href",
          "https://www.metabase.com/docs/v0.49/embedding/interactive-embedding-quick-start-guide.html?utm_source=oss&utm_media=embed-settings",
        );
      });

      it("should link to https://www.metabase.com/blog/why-full-app-embedding", async () => {
        await setupEnterprise({
          settingValues: { "enable-embedding": false },
        });

        expect(
          screen.getByText("offer multi-tenant, self-service analytics"),
        ).toHaveProperty(
          "href",
          "https://www.metabase.com/blog/why-full-app-embedding?utm_source=oss&utm_media=embed-settings",
        );
      });
    });
  });
  describe("when the embedding is enabled", () => {
    it("should allow going to static embedding settings page", async () => {
      const { history } = await setupEnterprise({
        settingValues: { "enable-embedding": true },
      });

      await goToStaticEmbeddingSettings();

      const location = history.getCurrentLocation();
      expect(location.pathname).toEqual(staticEmbeddingSettingsUrl);
    });

    it("should not allow going to interactive embedding settings page", async () => {
      const { history } = await setupEnterprise({
        settingValues: { "enable-embedding": true },
      });

      expect(
        screen.queryByRole("button", { name: "Configure" }),
      ).not.toBeInTheDocument();

      expect(
        screen.getByRole("link", { name: "Learn More" }),
      ).toBeInTheDocument();

      act(() => {
        history.push(interactiveEmbeddingSettingsUrl);
      });

      expect(history.getCurrentLocation().pathname).toEqual(
        embeddingSettingsUrl,
      );
    });
  });
});
