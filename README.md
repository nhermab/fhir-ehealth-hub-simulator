# Belgian eHealth Interhub FHIR Simulator (`fhir-ehealth-hub-simulator`)

> **Reference Server Implementation** for the **Belgian Federated eHealth Interhub FHIR Specification**, modernizing legacy KMEHR/SOAP transactions (`getTransactionList` and `getTransaction`) into **IHE MHD (Mobile access to Health Documents) on HL7® FHIR® R4**.

[![FHIR R4](https://img.shields.io/badge/FHIR-R4%20(v4.0.1)-orange.svg)](http://hl7.org/fhir/R4/)
[![IHE MHD](https://img.shields.io/badge/IHE%20MHD-v4.2.2-blue.svg)](https://profiles.ihe.net/ITI/MHD/)
[![Java](https://img.shields.io/badge/Java-17-green.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![HAPI FHIR](https://img.shields.io/badge/HAPI%20FHIR-7.6.0-blue.svg)](https://hapifhir.io/)

Built with **Spring Boot 3** and **HAPI FHIR 7.6.0**, this backend simulator implements the normative rules specified in `fhir-ig-specification/input/fsh/` and `fhir-ig-specification/input/pagecontent/`. It serves realistic, pre-configured FHIR JSON resources and binaries stored directly in a simple filesystem directory (`data/`).

---

## 1. Supported Interhub Transactions & Operations

The Implementation Guide is read-only and specifies **three normative transactions**. This server exposes those three and nothing else — no read, create, update, delete or history interaction, and no other resource type. Anything outside the surface below answers **HTTP 404** with an `OperationOutcome` (`issue.code = not-supported`) naming the three transactions.

| Transaction / Interaction | HTTP Method & Wire Endpoint | Implementation Details |
| :--- | :--- | :--- |
| **`getTransactionList`**<br/>*(MHD ITI-67 Find DocumentReferences)* | `POST [base]/DocumentReference/_search`<br/>(`application/x-www-form-urlencoded` body)<br/>*`GET [base]/DocumentReference` is tolerated for generic IHE conformance testing only* | • Mandatory `patient.identifier` validation (Belgian SSIN / INSS); a system other than the two national SSIN systems is refused with HTTP 400.<br/>• Filters: `category`, `type` (LOINC), `date`, `author.identifier`, `status`, `_id`, `identifier`, `searchtype`, `_count`, `_sort`.<br/>• `status` defaults to `current`; an unsupported `_sort` or `searchtype` is refused rather than silently ignored.<br/>• Returns FHIR `Bundle` (`type = searchset`) with `search.mode = match`; `Bundle.total` counts every match, not the page.<br/>• Paging stays on POST: `Bundle.link[relation=next]` carries an **opaque `_continuation` token**, so the patient SSIN never appears in a link URL.<br/>• Full partial failure support: downstream repository timeout/maintenance yields HTTP 200 OK with `OperationOutcome` at `search.mode = outcome`. A `searchtype=local` search does not fan out and therefore reports none. |
| **`getTransaction`**<br/>*(`$retrieve-document`, gatewaying MHD ITI-68)* | `POST [base]/DocumentReference/$retrieve-document`<br/>(`application/fhir+json` Parameters body) | • Accepts `Parameters.parameter[name="documentReference"]`, either as a relative reference or as a logical reference carrying only `Reference.identifier`.<br/>• Resolves it to a self-contained FHIR document Bundle (`type = document`).<br/>• Content negotiation: `Accept: application/pdf` streams the hub's own rendering as raw binary with HTTP 200 OK.<br/>• HTTP 410 Gone + `OperationOutcome` when the source system withdrew the document.<br/>• HTTP 404 Not Found + `OperationOutcome` when it does not exist.<br/>• HTTP 406 Not Acceptable + `OperationOutcome` when this hub publishes no PDF rendering of that document. |
| **Laboratory Observation Search**<br/>*(DIGIRELAB / IHE QEDm PCC-44)* | `POST [base]/Observation/_search`<br/>(`application/x-www-form-urlencoded` body) | • Mandatory `patient.identifier` (Belgian SSIN) and mandatory `code` (one or more LOINC analyte codes, e.g. `1558-6` Fasting Glucose, `2160-0` Serum Creatinine).<br/>• Filters: `code`, `category` (`laboratory`), `date`, `searchtype`, `_count`, `_sort`.<br/>• Returns discrete `BeInterhubLabObservation` resources with logical references only (`subject` by SSIN, `performer` by NIHDI/CBE, `derivedFrom` by source document uniqueId).<br/>• Traceable to source document: only observations from `current` reports are returned; end-to-end encrypted documents yield no observations.<br/>• Opaque `_continuation` token pagination on POST and partial failure warnings on federated fan-out. |
| **Capabilities Discovery** | `GET [base]/metadata` | Returns this server's `kind = instance` CapabilityStatement, which claims conformance to the IG's `BeInterhubDocumentResponder` requirements through `instantiates`. |

> **Path Forwarding**: Both `http://localhost:8080/fhir/...` (canonical base) and `http://localhost:8080/...` (without `/fhir` prefix) are supported transparently.

---

## 2. Directory Structure of Sample Data (`data/`)

All mock data is served from the `./data/` directory (configurable via `hub.simulator.data-dir`):

```
data/
├── document-references/
│   ├── DocumentReference-DocRefLabReportContainedExample.json   # MHD Comprehensive Contained Lab Report
│   ├── DocumentReference-DocRefLabReportExample.json            # Lab Report metadata alias
│   ├── DocumentReference-DocRefMinimalExample.json              # IHE MHD Minimal profile example
│   └── DocumentReference-DocRefTelemonitoringExample.json       # 24h Holter Ambulatory Telemonitoring
├── document-bundles/
│   ├── Bundle-BundleLabReportExample.json                       # Complete Lab Report Document Bundle (type=document)
│   └── Bundle-BundleTelemonitoringExample.json                  # Complete Telemonitoring Document Bundle (type=document)
├── searchsets/
│   └── Bundle-BundleTransactionListResponseExample.json         # Complete sample searchset with OperationOutcome
├── operation-outcomes/
│   └── OperationOutcome-OutcomePartialFailureExample.json       # Partial downstream repository timeout/maintenance
├── metadata/                                                    # IG reference copies; the served
│   ├── CapabilityStatement-BeInterhubDocumentResponder.json     # statement is generated at runtime
│   └── OperationDefinition-BeRetrieveDocument.json              # from what this server implements
├── binaries/
│   ├── rendered-lab-report-example-01.pdf                       # Hub-rendered sample lab report PDF
│   └── holter-001.pdf                                           # Holter telemonitoring report PDF
└── resources/
    ├── Patient-PatientPeeters.json                              # Jan Peeters (SSIN: 79080412345)
    ├── Practitioner-DrDanieleGovaerts.json                      # Clinical Biologist (NIHDI: 10000007999)
    ├── Practitioner-DrJeanDepondt.json                          # Cardiologist (NIHDI: 19876543201)
    ├── Organization-HubCoZo.json                                # CoZo Regional Hub (OID: 1.3.6.1.4.1.21297.1.3)
    ├── Organization-OrgUZLeuven.json                            # UZ Leuven Hospital (NIHDI: 71000012)
    └── (DiagnosticReports, Observations, Specimens, Devices)
```

To add additional clinical documents or patients to the simulator, simply drop your FHIR JSON or PDF files into the `data/` folder and restart or reload the service.

---

## 3. Prerequisites & Quickstart

### Prerequisites
- **Java 17+**
- **Apache Maven 3.8+**

### Build and Run

```bash
cd fhir-ehealth-hub-simulator

# 1. Run all 51 integration tests (each boots the app and hits real HTTP endpoints)
mvn clean test

# 2. Start the simulator server (runs on port 8080)
mvn spring-boot:run
```

Or run the packaged jar:
```bash
mvn package -DskipTests
java -jar target/fhir-ehealth-hub-simulator-1.0.0-SNAPSHOT.jar
```

### Pairing with the Web Viewer (`fhir-ehealthhub-simulator-viewer`)

You can explore and test this backend using the developer console in `../fhir-ehealthhub-simulator-viewer`:
1. Start this simulator on port 8080 (`mvn spring-boot:run`).
2. Start the viewer in another terminal (`cd ../fhir-ehealthhub-simulator-viewer && npm start`).
3. In the viewer at `http://localhost:4173`, navigate to **Connections** (`#settings`):
   - Set **Environment** to `Live · configured FHIR server`.
   - Set **FHIR base URL** to `http://localhost:8080/fhir`.
   - Set **HTTP transport** to `Local proxy · avoids browser CORS`.
4. Click **Save connection** and search for patient SSIN `79080412345`.

---

## 4. Live Examples & Curl Commands

### 4.1 Document Discovery (`getTransactionList` / MHD ITI-67)

#### Standard Query by Patient SSIN (POST form-urlencoded):
```bash
curl -X POST http://localhost:8080/fhir/DocumentReference/_search   -H "Content-Type: application/x-www-form-urlencoded"   -H "Accept: application/fhir+json; fhirVersion=4.0"   -d "patient.identifier=https%3A%2F%2Fwww.ehealth.fgov.be%2Fstandards%2Ffhir%2Fcore%2FNamingSystem%2Fssin%7C79080412345"
```

#### Filter by Belgian Document Category (`category=labresult`):
```bash
curl -X POST http://localhost:8080/fhir/DocumentReference/_search   -H "Content-Type: application/x-www-form-urlencoded"   -d "patient.identifier=79080412345&category=labresult"
```

#### Filter by Clinical LOINC Type (`type=http://loinc.org|18754-2` for Holter ECG):
```bash
curl -X POST http://localhost:8080/fhir/DocumentReference/_search   -H "Content-Type: application/x-www-form-urlencoded"   -d "patient.identifier=79080412345&type=http%3A%2F%2Floinc.org%7C18754-2"
```

#### Simulate Downstream Partial Failures (returns `search.mode = outcome` with `OperationOutcome`):
```bash
curl -X POST http://localhost:8080/fhir/DocumentReference/_search   -H "Content-Type: application/x-www-form-urlencoded"   -H "X-Simulate-Partial-Failure: true"   -d "patient.identifier=79080412345"
```

#### Missing Mandatory `patient.identifier` (Returns HTTP 400 Bad Request):
```bash
curl -i -X POST http://localhost:8080/fhir/DocumentReference/_search   -H "Content-Type: application/x-www-form-urlencoded"   -d "category=labresult"
```

#### Invalid / Malformed SSIN (Returns HTTP 400 Bad Request):
```bash
curl -i -X POST http://localhost:8080/fhir/DocumentReference/_search   -H "Content-Type: application/x-www-form-urlencoded"   -d "patient.identifier=12345"
```

---

## 4.2 Document Retrieval (`getTransaction` / MHD ITI-68 / `$retrieve-document`)

#### Retrieve Structured FHIR Document Bundle (Laboratory Report):
```bash
curl -X POST http://localhost:8080/fhir/DocumentReference/\$retrieve-document   -H "Content-Type: application/fhir+json"   -H "Accept: application/fhir+json; fhirVersion=4.0"   -d '{
    "resourceType": "Parameters",
    "parameter": [
      {
        "name": "documentReference",
        "valueReference": {
          "reference": "DocumentReference/DocRefLabReportContainedExample"
        }
      }
    ]
  }'
```

#### Retrieve Structured FHIR Document Bundle (Telemonitoring Report):
```bash
curl -X POST http://localhost:8080/fhir/DocumentReference/\$retrieve-document   -H "Content-Type: application/fhir+json"   -d '{
    "resourceType": "Parameters",
    "parameter": [
      {
        "name": "documentReference",
        "valueReference": {
          "reference": "DocumentReference/DocRefTelemonitoringExample"
        }
      }
    ]
  }'
```

#### Content Negotiation: Retrieve Hub-Rendered PDF Binary Stream:
```bash
curl -X POST http://localhost:8080/fhir/DocumentReference/\$retrieve-document   -H "Content-Type: application/fhir+json"   -H "Accept: application/pdf"   -d '{
    "resourceType": "Parameters",
    "parameter": [
      {
        "name": "documentReference",
        "valueReference": {
          "reference": "DocumentReference/DocRefLabReportContainedExample"
        }
      }
    ]
  }' --output lab-report.pdf
```

#### Withdrawn Document (Returns HTTP 410 Gone with `OperationOutcome`):
```bash
curl -i -X POST http://localhost:8080/fhir/DocumentReference/\$retrieve-document   -H "Content-Type: application/fhir+json"   -d '{
    "resourceType": "Parameters",
    "parameter": [
      {
        "name": "documentReference",
        "valueReference": {
          "reference": "DocumentReference/withdrawn"
        }
      }
    ]
  }'
```

#### Non-existent Document (Returns HTTP 404 Not Found with `OperationOutcome`):
```bash
curl -i -X POST http://localhost:8080/fhir/DocumentReference/\$retrieve-document   -H "Content-Type: application/fhir+json"   -d '{
    "resourceType": "Parameters",
    "parameter": [
      {
        "name": "documentReference",
        "valueReference": {
          "reference": "DocumentReference/non-existent-document"
        }
      }
    ]
  }'
```

---

#### No PDF Rendering Available (Returns HTTP 406 Not Acceptable with `OperationOutcome`):
```bash
curl -i -X POST http://localhost:8080/fhir/DocumentReference/\$retrieve-document   -H "Content-Type: application/fhir+json"   -H "Accept: application/pdf"   -d '{
    "resourceType": "Parameters",
    "parameter": [
      {
        "name": "documentReference",
        "valueReference": {
          "reference": "DocumentReference/DocRefMinimalExample"
        }
      }
    ]
  }'
```

---

## 4.3 Pagination, CapabilityStatement & the Refused Surface

#### Page 1, then the opaque POST continuation:
```bash
# The next link is [base]/DocumentReference/_search?_continuation=<opaque token>
curl -X POST http://localhost:8080/fhir/DocumentReference/_search   -H "Content-Type: application/x-www-form-urlencoded"   -d "patient.identifier=79080412345&_count=2"

# Replay the token in a new POST body; no other parameter is needed or honoured
curl -X POST http://localhost:8080/fhir/DocumentReference/_search   -H "Content-Type: application/x-www-form-urlencoded"   -d "_continuation=<opaque token>"
```

#### CapabilityStatement Discovery:
```bash
curl http://localhost:8080/fhir/metadata
```

#### Anything Outside the Two Transactions (Returns HTTP 404 with `not-supported`):
```bash
curl -i http://localhost:8080/fhir/Bundle/BundleLabReportExample
curl -i http://localhost:8080/fhir/DocumentReference/DocRefLabReportContainedExample
curl -i http://localhost:8080/fhir/Binary/rendered-lab-report-example-01
```

A `GET` on `_search` or `$retrieve-document` answers **HTTP 405** with an `Allow: POST` header: discovery and retrieval are POST-only so that patient identifiers never reach an access log.

---

## 5. Configuration Reference (`application.yml`)

```yaml
server:
  port: 8080

hub:
  simulator:
    # Filesystem directory containing sample resources and binaries
    data-dir: "./data"
    # Strict modulo-97 checksum enforcement on SSINs (false allows synthetic test data)
    strict-ssin-checksum: false
    # Return simulated partial failures by default on getTransactionList
    simulate-partial-failure: false
    # Responding Hub metadata
    hub-oid: "urn:oid:1.3.6.1.4.1.21297.1.3"
    hub-ehp: "1990000003"
    hub-name: "CoZo Regional Hub"
    server-base-url: "http://localhost:8080/fhir"
    # getTransactionList paging; continuation tokens stay opaque to the consumer
    default-page-size: 20
    max-page-size: 200
    continuation-token-ttl-seconds: 300
    continuation-token-cache-size: 500
    # Reference fragments answering 410 Gone instead of 404 Not Found on getTransaction.
    # A DocumentReference with status = entered-in-error is treated as withdrawn as well.
    withdrawn-references: [withdrawn, gone]
    # Documents this hub can also serve as its own rendered PDF (transactions.md §3.4).
    # Anything absent here answers 406 to Accept: application/pdf.
    pdf-renderings:
      DocRefLabReportContainedExample: rendered-lab-report-example-01.pdf
      DocRefLabReportExample: rendered-lab-report-example-01.pdf
      DocRefTelemonitoringExample: holter-001.pdf
```

## Hosted reference deployment

The viewer at https://dev.ehealthhub.be/ uses the API base https://dev-api.ehealthhub.be (without a `/fhir` suffix). The gateway supports root transaction paths and `/fhir/*`. Metadata and paging links advertise the public API base by default.

Set `HUB_SIMULATOR_SERVER_BASE_URL=http://localhost:8080/fhir` for local advertised links. Browser CORS defaults to `https://dev.ehealthhub.be`; override `HUB_SIMULATOR_VIEWER_ORIGIN` for another origin. CORS does not implement authentication or authorization.
