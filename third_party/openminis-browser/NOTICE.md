# OpenMinis Android browser — private integration

Source: https://github.com/OpenMinis/OpenMinis
Revision: 4ef29002e88db1e20e462ec2ff46916e8a7dcb45
License: upstream GPL version 3 (full text in LICENSE).

Adapted source is in app/src/main/kotlin/io/github/mangi/eta/agent/browser/ported/;
translated resources are in values*/openminis_browser.xml.
Theme.kt and ChatColors.kt from the same revision are included for browser-scoped
color, shape, typography and modal design parity.
Original upstream comments and source attribution retained. Armilla-specific adapter code
connects this browser to browser_use, paths, cancellation, output limits and UI.

Initially prepared for local use. The repository owner subsequently explicitly
requested pushing this integration to the public repository and running Actions.
Upstream GPL license and attribution are retained. The host project's license was
not changed; this notice does not assert license compatibility or relicense the
combined work. Distribution/authorization requirements remain to be resolved by
the repository owner. No automatic public Release is created by this integration.

Adaptation areas: package/resource isolation, application theme bridge, Linux guest
path mapping, bounded images/downloads/results, cookie offload, user takeover,
legacy browser tool actions, cancellation and lifecycle cleanup. See PORT_STATUS.md
for validation and remaining work. No OpenMinis agent runtime or model-provider
code is included.
