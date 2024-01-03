import React, { useState, useMemo } from 'react';
import styled from 'styled-components';
import { Button, Empty, message, Modal, Pagination, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { AlignType } from 'rc-table/lib/interface';
import { Link } from 'react-router-dom';
import { useListBusinessAttributesQuery } from '../../graphql/businessAttribute.generated';
import { useListAccessTokensQuery, useRevokeAccessTokenMutation } from '../../graphql/auth.generated';
import { Message } from '../shared/Message';
import TabToolbar from '../entity/shared/components/styled/TabToolbar';
import { StyledTable } from '../entity/shared/components/styled/StyledTable';
import CreateBusinessAttributeModal from './CreateBusinessAttributeModal';
import { scrollToTop } from '../shared/searchUtils';
import analytics, { EventType } from '../analytics';
import { useUserContext } from '../context/useUserContext';
import { useAppConfig } from '../useAppConfig';
import { FacetFilterInput, BusinessAttribute } from '../../types.generated';
import { SearchBar } from '../search/SearchBar';
import { useEntityRegistry } from '../useEntityRegistry';
import useTagsAndTermsRenderer from './utils/useTagsAndTermsRenderer';
import useDescriptionRenderer from './utils/useDescriptionRenderer';
import BusinessAttributeItemMenu from './BusinessAttributeItemMenu';

function BusinessAttributeListMenuColumn(handleDelete: (urn: string) => void) {
    return (record: BusinessAttribute) => (
        <BusinessAttributeItemMenu
            title={record.properties?.name}
            urn={record.urn}
            onDelete={() => handleDelete(record.urn)}
        />
    );
}

const SourceContainer = styled.div`
    width: 100%;
    padding-top: 20px;
    padding-right: 40px;
    padding-left: 40px;
    display: flex;
    flex-direction: column;
    overflow: auto;
`;

const TokensContainer = styled.div`
    padding-top: 0px;
`;

const TokensHeaderContainer = styled.div`
    && {
        padding-left: 0px;
    }
`;

const TokensTitle = styled(Typography.Title)`
    && {
        margin-bottom: 8px;
    }
`;

const PaginationContainer = styled.div`
    display: flex;
    justify-content: center;
`;

const searchBarStyle = {
    maxWidth: 220,
    padding: 0,
};

const searchBarInputStyle = {
    height: 32,
    fontSize: 12,
};

const DEFAULT_PAGE_SIZE = 10;

export const BusinessAttributes = () => {
    const [isCreatingToken, setIsCreatingToken] = useState(false);
    const [removedTokens, setRemovedTokens] = useState<string[]>([]);
    const entityRegistry = useEntityRegistry();

    // Current User Urn
    const authenticatedUser = useUserContext();
    const currentUserUrn = authenticatedUser?.user?.urn || '';

    const isTokenAuthEnabled = useAppConfig().config?.authConfig?.tokenAuthEnabled;
    const canGeneratePersonalAccessTokens =
        isTokenAuthEnabled && authenticatedUser?.platformPrivileges?.generatePersonalAccessTokens;

    // Access Tokens list paging.
    const [page, setPage] = useState(1);
    const pageSize = DEFAULT_PAGE_SIZE;
    const start = (page - 1) * pageSize;
    const [query, setQuery] = useState<undefined | string>(undefined);
    const [tagHoveredUrn, setTagHoveredUrn] = useState<string | undefined>(undefined);

    const {
        loading: businessAttributeLoading,
        error: businessAttributeError,
        data: businessAttributeData,
        refetch: businessAttributeRefetch,
    } = useListBusinessAttributesQuery({
        variables: {
            query,
        },
    });
    const descriptionRender = useDescriptionRenderer(businessAttributeRefetch);
    const tagRenderer = useTagsAndTermsRenderer(
        tagHoveredUrn,
        setTagHoveredUrn,
        {
            showTags: true,
            showTerms: false,
        },
        query || '',
        businessAttributeRefetch,
    );

    const termRenderer = useTagsAndTermsRenderer(
        tagHoveredUrn,
        setTagHoveredUrn,
        {
            showTags: false,
            showTerms: true,
        },
        query || '',
        businessAttributeRefetch,
    );

    // Filters for Access Tokens list
    const filters: Array<FacetFilterInput> = [
        {
            field: 'ownerUrn',
            values: [currentUserUrn],
        },
    ];

    // Call list Access Token Mutation
    const {
        loading: tokensLoading,
        error: tokensError,
        data: tokensData,
        refetch: tokensRefetch,
    } = useListAccessTokensQuery({
        skip: !canGeneratePersonalAccessTokens,
        variables: {
            input: {
                start,
                count: pageSize,
                filters,
            },
        },
    });
    console.log('l', businessAttributeLoading);
    console.log('e', businessAttributeError);

    const totalBusinessAttributes = businessAttributeData?.listBusinessAttributes?.total || 0;
    const businessAttributes = useMemo(
        () => businessAttributeData?.listBusinessAttributes?.businessAttributes || [],
        [businessAttributeData],
    );

    // Any time a access token  is removed or created, refetch the list.
    const [revokeAccessToken, { error: revokeTokenError }] = useRevokeAccessTokenMutation();

    // Revoke token Handler
    const onRemoveToken = (token: any) => {
        Modal.confirm({
            title: 'Are you sure you want to revoke this token?',
            content: `Anyone using this token will no longer be able to access the DataHub API. You cannot undo this action.`,
            onOk() {
                const newTokenIds = [...removedTokens, token.id];
                setRemovedTokens(newTokenIds);

                revokeAccessToken({ variables: { tokenId: token.id } })
                    .then(({ errors }) => {
                        if (!errors) {
                            analytics.event({ type: EventType.RevokeAccessTokenEvent });
                        }
                    })
                    .catch((e) => {
                        message.destroy();
                        message.error({ content: `Failed to revoke Token!: \n ${e.message || ''}`, duration: 3 });
                    })
                    .finally(() => {
                        setTimeout(() => {
                            tokensRefetch?.();
                        }, 3000);
                    });
            },
            onCancel() {},
            okText: 'Yes',
            maskClosable: true,
            closable: true,
        });
    };
    console.log(onRemoveToken);
    const onTagTermCell = (record: BusinessAttribute) => ({
        onMouseEnter: () => {
            setTagHoveredUrn(record.urn);
        },
        onMouseLeave: () => {
            setTagHoveredUrn(undefined);
        },
    });

    const handleDelete = (urn: string) => {
        // removeFromListPostCache(client, urn, page, pageSize);
        console.log('urn ', urn);
        setTimeout(() => {
            businessAttributeRefetch?.();
        }, 2000);
    };

    // const tableData = businessAttributes?.map(
    //     (businessAttribute): BusinessAttribute => ({
    //         urn: businessAttribute.urn,
    //         type: businessAttribute.type,
    //         properties: businessAttribute.properties
    //             ? {
    //                   name: businessAttribute.properties.name,
    //                   description: businessAttribute.properties.description,
    //                   tags: businessAttribute.properties.tags
    //                       ? {
    //                             __typename: businessAttribute.properties.tags.__typename,
    //                             tags: businessAttribute.properties.tags.tags?.map((tag) => ({
    //                                 ...tag,
    //                                 tag: {
    //                                     ...tag.tag,
    //                                 },
    //                             })),
    //                         }
    //                       : undefined,
    //                   glossaryTerms: businessAttribute.properties.glossaryTerms,
    //                   type: businessAttribute.properties.type,
    //                   created: businessAttribute.properties.created,
    //                   lastModified: businessAttribute.properties.lastModified,
    //               }
    //             : undefined,
    //     }),
    // );
    const tableData = businessAttributes;
    const tableColumns = [
        {
            width: '20%',
            title: 'Name',
            dataIndex: ['properties', 'name'],
            key: 'name',
            render: (name: string, record: any) => (
                <Link to={`${entityRegistry.getEntityUrl(record.type, record.urn)}`}>{name}</Link>
            ),
        },
        {
            title: 'Description',
            dataIndex: ['properties', 'description'],
            key: 'description',
            // render: (description: string) => description || '',
            render: descriptionRender,
        },
        {
            width: '20%',
            title: 'Tags',
            dataIndex: ['properties', 'tags'],
            key: 'tags',
            render: tagRenderer,
            onCell: onTagTermCell,
        },
        {
            width: '20%',
            title: 'Glossary Terms',
            dataIndex: ['properties', 'glossaryTags'],
            key: 'glossaryTags',
            render: termRenderer,
            onCell: onTagTermCell,
        },
        {
            width: '13%',
            title: 'Data Type',
            dataIndex: ['properties', 'businessAttributeDataType'],
            key: 'businessAttributeDataType',
            render: (dataType: string) => dataType || '',
        },
        {
            title: '',
            dataIndex: '',
            width: '5%',
            align: 'right' as AlignType,
            key: 'menu',
            render: BusinessAttributeListMenuColumn(handleDelete),
        },
    ];

    const onChangePage = (newPage: number) => {
        scrollToTop();
        setPage(newPage);
    };
    console.log(tableData);

    return (
        <SourceContainer>
            {tokensLoading && !tokensData && (
                <Message type="loading" content="Loading businessAttributes..." style={{ marginTop: '10%' }} />
            )}
            {tokensError && message.error('Failed to load businessAttributes :(')}
            {revokeTokenError && message.error('Failed to update the Token :(')}
            <TokensContainer>
                <TokensHeaderContainer>
                    <TokensTitle level={2}>Business Attribute</TokensTitle>
                    <Typography.Paragraph type="secondary">View your Business Attributes</Typography.Paragraph>
                </TokensHeaderContainer>
            </TokensContainer>
            <TabToolbar>
                <Button
                    type="text"
                    onClick={() => setIsCreatingToken(true)}
                    data-testid="add-token-button"
                    disabled={!canGeneratePersonalAccessTokens}
                >
                    <PlusOutlined /> Create Business Attribute
                </Button>
                <SearchBar
                    initialQuery=""
                    placeholderText="Search Views..."
                    suggestions={[]}
                    style={searchBarStyle}
                    inputStyle={searchBarInputStyle}
                    onSearch={() => null}
                    onQueryChange={(q) => setQuery(q.length > 0 ? q : undefined)}
                    entityRegistry={entityRegistry}
                />
            </TabToolbar>
            <StyledTable
                columns={tableColumns}
                dataSource={tableData}
                rowKey="urn"
                locale={{
                    emptyText: <Empty description="No Business Attributes!" image={Empty.PRESENTED_IMAGE_SIMPLE} />,
                }}
                pagination={false}
            />
            <PaginationContainer>
                <Pagination
                    style={{ margin: 40 }}
                    current={page}
                    pageSize={pageSize}
                    total={totalBusinessAttributes}
                    showLessItems
                    onChange={onChangePage}
                    showSizeChanger={false}
                />
            </PaginationContainer>
            <CreateBusinessAttributeModal visible={isCreatingToken} onClose={() => setIsCreatingToken(false)} />
        </SourceContainer>
    );
};
