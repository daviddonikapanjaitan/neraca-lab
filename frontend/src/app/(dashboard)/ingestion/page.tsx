import { redirect } from "next/navigation"

import { INGESTION_HOME } from "@/lib/ingestion-sections"

/** /ingestion: the Ingestion pages open on IDX XBRL. */
export default function Ingestion() {
  redirect(INGESTION_HOME)
}
