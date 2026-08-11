import { Outlet, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ConversationList } from '@/features/conversations/ConversationList';

/**
 * Two-pane chat shell: left = `ConversationList`; right = nested route via
 * `<Outlet/>`. Selecting a conversation in the left pane navigates to
 * `/chat/<id>`; the "+" header button navigates to `/chat/new`. When the
 * route carries `?agentId=<id>`, the list scopes itself to that agent and
 * preserves the param across selection (US-06-007).
 */
export default function ChatPage(): JSX.Element {
  const navigate = useNavigate();
  const params = useParams<{ conversationId?: string }>();
  const [search] = useSearchParams();
  const agentId = search.get('agentId') ?? undefined;
  const isValidAgentId = agentId !== undefined && /^[0-9a-f-]{36}$/i.test(agentId);
  const filterAgentId = isValidAgentId ? agentId : undefined;

  const preserveAgentSuffix = filterAgentId ? `?agentId=${filterAgentId}` : '';

  return (
    <div className="grid h-full min-h-0 grid-cols-1 md:grid-cols-[320px_1fr]">
      <aside className="hidden border-r border-border-default bg-bg-base md:block">
        <ConversationList
          activeConversationId={params.conversationId}
          agentId={filterAgentId}
          onSelect={(id) => navigate(`/chat/${id}${preserveAgentSuffix}`)}
          onNew={() => navigate('/chat/new')}
        />
      </aside>
      <section className="flex min-h-0 min-w-0 flex-col bg-bg-base">
        <Outlet />
      </section>
    </div>
  );
}
