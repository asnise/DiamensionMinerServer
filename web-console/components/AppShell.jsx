"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Home, User, ShoppingBag, Store, Factory } from "lucide-react";
import { ToastProvider } from "@/components/Toast";

const NAV = [
  {
    href: "/",
    icon: Home,
    label: "Overview",
    match: (p) => p === "/",
  },
  {
    href: "/shop",
    icon: ShoppingBag,
    label: "Online Shop",
    match: (p) => p.startsWith("/shop"),
  },
  {
    href: "/market",
    icon: Store,
    label: "Market",
    match: (p) => p.startsWith("/market"),
  },
  {
    href: "/jobs",
    icon: Factory,
    label: "Workspace Jobs",
    match: (p) => p.startsWith("/jobs"),
  },
  {
    href: "/profile",
    icon: User,
    label: "Player Profile",
    match: (p) => p.startsWith("/profile"),
  },
];

export default function AppShell({ children }) {
  const pathname = usePathname();
  const isAdmin = pathname.startsWith("/admin");

  return (
    <ToastProvider>
      <div className="shell">
        <aside className="side-nav">
          {NAV.map(({ href, icon: Icon, label, match }) => (
            <Link
              key={href}
              href={href}
              className={`nav-icon ${match(pathname) ? "active" : ""}`}
              title={label}
              aria-label={label}
            >
              <Icon size={20} strokeWidth={2} />
            </Link>
          ))}
        </aside>

        <main className={`page-frame${isAdmin ? " wide" : ""}`}>
          <div className="frame-inner">
            {children}
            {!isAdmin && (
              <footer className="site-footer">
                read-only demo console · data from PostgreSQL
              </footer>
            )}
          </div>
        </main>
      </div>
    </ToastProvider>
  );
}