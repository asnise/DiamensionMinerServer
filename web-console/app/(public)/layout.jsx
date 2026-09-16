export const metadata = {
  title: 'Dimensional Miner',
};

export default function PublicLayout({ children }) {
  return (
    <>
      <header className="topbar">
        <nav>
          <a className="brand" href="/">Dimensional Miner Console</a>
        </nav>
      </header>
      <main>{children}</main>
      <footer>read-only demo console · data from PostgreSQL</footer>
    </>
  );
}
