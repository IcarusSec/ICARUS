/**
 * ICARUS template builder — public links.
 *
 * Donation buttons stay hidden until the URL is a real https link.
 * Replace YOUR_HANDLE (and only that) with the account that should
 * receive support. Do not commit tokens or private keys here.
 *
 *   buyMeACoffee   https://www.buymeacoffee.com/YOUR_HANDLE
 *   kofi           https://ko-fi.com/YOUR_HANDLE
 *   githubSponsors https://github.com/sponsors/YOUR_HANDLE
 *                  (user or organization, e.g. IcarusSec)
 *
 * bappUrl: paste the PortSwigger listing when it exists, for example
 *   https://portswigger.net/bappstore/0123456789abcdef
 * Until then the site links to the BApp Store home.
 */
window.ICARUS_SITE_CONFIG = {
  repoUrl: "https://github.com/IcarusSec/ICARUS",
  docsUrl: "https://github.com/IcarusSec/ICARUS/tree/main/docs",
  bappStoreHome: "https://portswigger.net/bappstore",
  bappUrl: "",
  donations: {
    buyMeACoffee: "https://www.buymeacoffee.com/YOUR_HANDLE",
    kofi: "https://ko-fi.com/YOUR_HANDLE",
    githubSponsors: "https://github.com/sponsors/YOUR_HANDLE"
  }
};
