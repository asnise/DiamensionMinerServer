export default async function AdminLoginPage({ searchParams }) {
  const params = await searchParams;
  const error = params?.error === "1";

  return (
    <div className="login-box">
      <h1>Admin login</h1>
      {error && <p className="login-error">Invalid username or password.</p>}
      <form action="/api/admin/login" method="POST">
        <div>
          <label htmlFor="username">Username</label>
          <input
            id="username"
            name="username"
            type="text"
            autoComplete="username"
            required
            autoFocus
          />
        </div>
        <div>
          <label htmlFor="password">Password</label>
          <input
            id="password"
            name="password"
            type="password"
            autoComplete="current-password"
            required
          />
        </div>
        <button type="submit">Sign in</button>
      </form>
    </div>
  );
}