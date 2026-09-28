# Invary Integrity Measurement - HPE Morpheus Plugin

## Overview

The Invary Integrity Measurement plugin integrates Invary's On-Premise Runtime
Integrity solution into your HPE Morpheus appliance, allowing you to monitor the
integrity of your fleet from the console you already use to manage it.

Invary verifies what your other tools assume. Most of the security stack detects known
threats; Invary confirms that a running system still matches what it was built to be. An
Invary Sensor continuously measures the runtime memory state of a machine, and an Appraiser
running off that machine compares each measurement against a baseline captured from a clean,
known-good system. Any deviation is surfaced, whether or not it matches a known attack
pattern, and because appraisal happens off-system a compromised machine cannot vouch for
itself. The technology is licensed from the NSA and follows the MITRE Continuous Remote
Attestation Framework. It is available as SaaS or as a fully air-gapped on-premise
deployment; this plugin integrates the on-premise deployment.


## Features

Once installed and configured, the plugin adds the following to your Morpheus appliance:

- **Integrity tab on Instances and Servers** - show runtime integrity status for a
  clustered service or an individual machine, alongside the tabs you already use to manage
  it.
- **Runtime Integrity Analytics** - fleet-wide integrity standing across every instance the
  Appraiser has appraised.
- **Invary Runtime Integrity Report** - an inventory report summarizing appraisal results
  across managed servers, available on demand or on a schedule.
- **Invary Sensor Install Task** - an automation task that installs the Invary Sensor on a
  managed instance, usable on its own or as part of a workflow or provisioning blueprint.
- **Augmented monitoring** - integrity is folded into the health monitoring you already
  watch. The Appraiser adds an integrity check for each managed machine, so a failed
  appraisal is reflected in that machine's Morpheus health and can raise an incident.
- **Automatic remediation on integrity failure** - when a machine fails an appraisal, the
  plugin can take a configured action on the instance: `Stop`, `Restart`, `Suspend`,
  `Revert`, or `None` to alert only. The action is set as a plugin-wide default and can be
  overridden per instance.


## Requirements

- **HPE Morpheus 6.3.0 or later.**
- **A licensed Invary On-Premise deployment** - at minimum an Invary Appraiser
  reachable from the Morpheus appliance. The plugin is a front end for that Appraiser; it
  does not perform measurement or appraisal itself and does nothing without one.
- **Network connectivity** - the Morpheus appliance must reach the Appraiser's API endpoint
  (HTTPS, normally port 8443), and each managed machine running a Sensor must reach the
  Appraiser's sensor endpoint.

If you do not yet have an Invary deployment, contact Invary to arrange a trial:
**info@invary.com** or via <https://invary.com/contact>.


## Installation

Installation is four steps, and the `morpheus setup` command generates most of the values
you need:

1. **Create an OAuth client** on the Morpheus appliance for the integration to use.
2. **Run `invary-appraiser morpheus setup`** to configure the Appraiser's side of the
   integration. It reports the values needed by the remaining steps.
3. **Update the Invary Appraiser configuration** with the values `morpheus setup` provides,
   and restart the Appraiser.
4. **Upload `invary-hpe-morpheus-plugin-<version>.jar`** on the Morpheus
   *Administration -> Integrations -> Plugins* page, then open its settings and fill them in
   with the values `morpheus setup` provides - the Appraiser API URL and API token, and your
   Customer Package URL.

Uploading a newer jar replaces the installed plugin rather than adding a second copy.


## Documentation

For detailed installation, configuration and operational guidance, see the
**[Invary + HPE Morpheus - Integration Guide](https://developers.invary.com/Invary%20%2B%20HPE%20Morpheus%20-%20Integration%20Guide.pdf)**.


## License

Licensed under the Apache License, Version 2.0.
