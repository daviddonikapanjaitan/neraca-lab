"use client"

import { Fragment } from "react"
import Link from "next/link"
import { usePathname } from "next/navigation"

import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb"

interface Crumb {
  label: string
  href: string
}

const ADMIN_PAGES: Record<string, string> = {
  users: "User Management",
  roles: "Role Management",
}

/**
 * /companies                -> Companies
 * /companies/IDX/HRTA       -> Companies / IDX / HRTA
 * /admin/users              -> Admin Center / User Management
 * The exchange crumb opens the company list filtered by that exchange.
 */
function crumbs(pathname: string): Crumb[] {
  const segments = pathname.split("/").filter(Boolean).map((s) => decodeURIComponent(s))
  if (segments[0] === "admin") {
    const page = ADMIN_PAGES[segments[1] ?? ""]
    return page
      ? [{ label: "Admin Center", href: "/admin/users" }, { label: page, href: pathname }]
      : [{ label: "Admin Center", href: "/admin/users" }]
  }
  if (segments[0] !== "companies") {
    return segments.map((s, i) => ({
      label: s.charAt(0).toUpperCase() + s.slice(1),
      href: "/" + segments.slice(0, i + 1).join("/"),
    }))
  }
  const result: Crumb[] = [{ label: "Companies", href: "/companies" }]
  const [, exchange, ticker] = segments
  if (exchange) {
    const code = exchange.toUpperCase()
    result.push({ label: code, href: `/companies?exchange=${encodeURIComponent(code)}` })
  }
  if (ticker) {
    result.push({ label: ticker.toUpperCase(), href: pathname })
  }
  return result
}

export function DynamicBreadcrumb() {
  const pathname = usePathname()
  const items = crumbs(pathname)

  if (items.length === 0) return null

  return (
    <Breadcrumb>
      <BreadcrumbList>
        {items.map((item, index) => {
          const isLast = index === items.length - 1
          // the first crumb (and its separator) is hidden on mobile when there are more
          const hideOnMobile = index === 0 && items.length > 1
          return (
            // item and separator are sibling <li>s (an <li> inside an <li> breaks hydration)
            <Fragment key={item.href + index}>
              <BreadcrumbItem className={hideOnMobile ? "hidden md:inline-flex" : undefined}>
                {isLast ? (
                  <BreadcrumbPage>{item.label}</BreadcrumbPage>
                ) : (
                  <BreadcrumbLink render={<Link href={item.href} />}>{item.label}</BreadcrumbLink>
                )}
              </BreadcrumbItem>
              {!isLast && <BreadcrumbSeparator className={hideOnMobile ? "hidden md:block" : undefined} />}
            </Fragment>
          )
        })}
      </BreadcrumbList>
    </Breadcrumb>
  )
}
