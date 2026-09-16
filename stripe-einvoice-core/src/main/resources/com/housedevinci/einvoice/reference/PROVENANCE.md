# Provenance of the vendored validation artefacts

Nothing here is downloaded at build time or at run time (D-24). Every file is read from the
classpath, through `VendoredArtefacts`, which refuses a path that is not in `CHECKSUMS.txt`; the
SHA-256 of every file is recomputed on every build by `VendoredArtefactsTest`, so a file that
changed without its checksum changing fails the build. A stylesheet is executable code and these
four are run over documents that will be filed with a tax authority: a silent swap is the whole
threat.

Retrieved 2026-09-16 over HTTPS, directly from the publisher named in each section.

## OASIS UBL 2.1 XSD (15 files, `ubl/2.1/`)

- Source: `https://docs.oasis-open.org/ubl/os-UBL-2.1/xsd/maindoc/` and `.../xsd/common/`, the
  OASIS Standard distribution, carried over byte for byte from this owner's Morocco e-invoicing
  module, which fetched them from OASIS on 2026-09-10.
- Files: `UBL-Invoice-2.1.xsd`, `UBL-CreditNote-2.1.xsd` and the transitive closure of their
  `schemaLocation` imports, resolved until the set closed.
- Terms: the OASIS IPR policy applies to the OASIS Standard. Each file carries the OASIS copyright
  notice in its header, preserved byte for byte.
- What they are for: structural validation of the exact bytes before any schematron runs. They are
  not a conformance statement; EN 16931 conformance is what the schematron below decides.

## CEN EN 16931 schematron, UBL syntax (`schematron/en16931/EN16931-UBL-validation.xsl`)

- Source: `https://github.com/itplr-kosit/validator-configuration-xrechnung/releases/download/v2026-08-31/xrechnung-3.0.2-validator-configuration-2026-08-31.zip`,
  entry `EN16931-UBL-validation.xsl`.
- Release: `v2026-08-31` (XRechnung 3.0.2 validator configuration), published 2026-09-02.
- Upstream of that release: `ConnectingEurope/eInvoicing-EN16931`, the European Commission's
  reference validation artefacts for EN 16931.
- Licence: Apache-2.0.
- This is the compiled form of the CEN rules (`BR-*`, `BR-CO-*`, `BR-S-*`, `BR-AE-*`, ...). It is
  taken from the KoSIT release rather than compiled here so that the rules our XRechnung documents
  are judged by are byte-identical to the rules the German reference validator applies.

## CIUS XRechnung 3.0.2 schematron, UBL syntax (`schematron/xrechnung/XRechnung-UBL-validation.xsl`)

- Source: the same release zip, entry `resources/xrechnung/3.0.2/xsl/XRechnung-UBL-validation.xsl`.
- Release: `v2026-08-31`, XRechnung version **3.0.2**, published 2026-09-02.
- Publisher: Koordinierungsstelle fuer IT-Standards (KoSIT), on behalf of the IT-Planungsrat.
- Licence: Apache-2.0.
- Specification identifier this version demands (read out of the stylesheet itself, variable
  `XR-CIUS-ID`, not out of prose):
  `urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0`
- Versions and bundles: `https://xeinkauf.de/xrechnung/versionen-und-bundles/`

## Peppol BIS Billing 3.0 schematron, UBL syntax (`schematron/peppol/`)

- Files: `CEN-EN16931-UBL.xslt` (the CEN rules as OpenPeppol compiles them) and
  `PEPPOL-EN16931-UBL.xslt` (the Peppol-specific rules, `PEPPOL-EN16931-R*` and the `CL*` code-list
  rules).
- Source: `https://repo1.maven.org/maven2/com/helger/phive/rules/phive-rules-peppol/4.5.6/phive-rules-peppol-4.5.6.jar`,
  entries `external/schematron/openpeppol/2026.5/xslt/`.
- Release: OpenPeppol **2026.5** (the spring 2026 Peppol release), as redistributed in
  phive-rules-peppol 4.5.6.
- Licence: Apache-2.0 (phive-rules and the OpenPeppol artefacts it carries).
- Why from the phive artefact and not from `OpenPEPPOL/peppol-bis-invoice-3`: that repository
  publishes the `.sch` source only. Compiling schematron to XSLT here would mean shipping a
  stylesheet nobody else has ever run; taking the compiled form that the Peppol ecosystem's own
  validators use means a document that passes here passes at an access point.
- Specification identifier this release demands:
  `urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0`

## The XSLT processor is not vendored, and is not a dependency

These four stylesheets are XSLT 2.0. The JDK ships an XSLT 1.0 processor only. The processor this
module runs them with is supplied by the host application, by class name, and is **test scope**
here: the only practical XSLT 2.0 processor for the JVM is Saxon-HE, which is MPL-2.0, and this
repository's licence gate denies MPL for anything it ships. With no processor on the classpath the
schematron validator reports `NOT_EVALUATED`, which the issuance unit of work treats as a refusal,
so an application without one issues nothing rather than issuing something unvalidated. See
`README.md` and `docs/documents.md`.
