export function ErrorBanner({ error }: { error: Error | undefined }) {
  if (!error) return null;
  return (
    <div className="banner banner-error" role="alert">
      {error.message}
    </div>
  );
}

export function Empty({ children }: { children: React.ReactNode }) {
  return <div className="empty">{children}</div>;
}
